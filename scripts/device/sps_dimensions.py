#!/usr/bin/env python3
"""Decode coded width/height out of an H.264 SPS NAL unit.

Device-testing helper: an RTSP DESCRIBE carries the stream's SPS in the
SDP `sprop-parameter-sets` attribute (base64), and /ws/video config frames
carry it raw. This script turns those bytes into the coded dimensions the
encoder is actually emitting — the wire-level truth behind the hub's
"Encoded stream started (h264 WxH…)" logcat lines.

Input (first positional arg, or stdin): hex bytes, base64 (an
sprop-parameter-sets value), or an Annex-B file path. Start codes, NAL
headers and emulation-prevention bytes (00 00 03) are all handled.

    ./sps_dimensions.py 6742c028d940501d9000            # hex
    ./sps_dimensions.py --selftest                      # built-in vectors

Verification status: --selftest checks hand-built vectors (baseline
1280x720, high-profile 1920x1080 with 1088-coded height + bottom crop,
4:2:0 horizontal cropping). On a device round, confirm once against the
hub's own logcat dimension lines for the same stream — they must agree.
"""

import base64
import binascii
import re
import sys

# profile_idc values that prefix the chroma/bit-depth/scaling syntax
_HIGH_PROFILES = {100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135}
# chroma_format_idc -> (SubWidthC, SubHeightC)
_SUB_C = {1: (2, 2), 2: (2, 1), 3: (1, 1)}


class BitReader:
    def __init__(self, data):
        self.data = data
        self.pos = 0  # bit position

    def u(self, n):
        v = 0
        for _ in range(n):
            byte = self.data[self.pos >> 3]
            bit = (byte >> (7 - (self.pos & 7))) & 1
            v = (v << 1) | bit
            self.pos += 1
        return v

    def ue(self):
        zeros = 0
        while self.u(1) == 0:
            zeros += 1
            if zeros > 32:
                raise ValueError("ue(v) leading-zero run too long — not a valid SPS")
        return (1 << zeros) - 1 + (self.u(zeros) if zeros else 0)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k % 2 else -(k // 2)


def strip_emulation(data):
    # 00 00 03 -> 00 00 (only the 03 byte is the escape payload; 00 00 00,
    # 01, 02 following a 00 00 pair are start codes / other payloads and
    # survive untouched)
    out = bytearray()
    i = 0
    while i < len(data):
        if i + 2 < len(data) and data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 3:
            out += b"\x00\x00"
            i += 3
            continue
        out.append(data[i])
        i += 1
    return bytes(out)


def find_sps(data):
    # Accept Annex-B: scan start-code-prefixed NALs for type 7. A bare SPS
    # (first byte already 0x67/0x42/…) passes through untouched.
    nals = []
    starts = [m.start() for m in re.finditer(b"\x00\x00\x01", data)]
    if starts:
        for idx, s in enumerate(starts):
            body = data[s + 3 : starts[idx + 1] if idx + 1 < len(starts) else len(data)]
            nals.append(body)
    else:
        nals = [data]
    for nal in nals:
        if nal and (nal[0] & 0x1F) == 7:
            return nal
    raise ValueError("no SPS NAL (nal_unit_type 7) found in input")


def parse_sps(nal):
    r = BitReader(strip_emulation(nal[1:]))  # skip NAL header byte
    profile_idc = r.u(8)
    r.u(8)  # constraint_set flags + reserved
    r.u(8)  # level_idc
    r.ue()  # seq_parameter_set_id
    sub_c = (2, 2)  # default 4:2:0 for the high-profile branch
    chroma_idc = 1
    separate_planes = 0
    if profile_idc in _HIGH_PROFILES:
        chroma_idc = r.ue()
        if chroma_idc in _SUB_C:
            sub_c = _SUB_C[chroma_idc]
        else:
            raise ValueError(f"bad chroma_format_idc {chroma_idc}")
        if chroma_idc == 3:
            separate_planes = r.u(1)
        r.ue()  # bit_depth_luma_minus8
        r.ue()  # bit_depth_chroma_minus8
        r.u(1)  # qpprime_y_zero_transform_bypass_flag
        if r.u(1):  # seq_scaling_matrix_present_flag
            lists = 12 if (chroma_idc == 3 and not separate_planes) else 8
            for i in range(lists):
                if r.u(1):  # seq_scaling_list_present_flag[i]
                    size = 16 if i < 6 else 64
                    last = next_ = 8
                    for _ in range(size):
                        if next_ != 0:
                            delta = r.se()
                            next_ = (last + delta + 256) % 256
                        last = next_ if next_ != 0 else last
    r.ue()  # log2_max_frame_num_minus4
    poc_type = r.ue()
    if poc_type == 0:
        r.ue()  # log2_max_pic_order_cnt_lsb_minus4
    elif poc_type == 1:
        r.u(1)  # delta_pic_order_always_zero_flag
        r.se()  # offset_for_non_ref_pic
        r.se()  # offset_for_top_to_bottom_field
        for _ in range(r.ue()):  # num_ref_frames_in_pic_order_cnt_cycle
            r.se()
    r.ue()  # max_num_ref_frames
    r.u(1)  # gaps_in_frame_num_value_allowed_flag
    width_mbs = (r.ue() + 1) * 16
    height_maps = (r.ue() + 1) * 16
    frame_mbs_only = r.u(1)
    if not frame_mbs_only:
        r.u(1)  # mb_adaptive_frame_field_flag
    r.u(1)  # direct_8x8_inference_flag
    crop_l = crop_r = crop_t = crop_b = 0
    if r.u(1):  # frame_cropping_flag
        crop_l, crop_r, crop_t, crop_b = r.ue(), r.ue(), r.ue(), r.ue()
    chroma_array_type = 0 if separate_planes else chroma_idc
    crop_unit_x = 1 if chroma_array_type == 0 else sub_c[0]
    crop_unit_y = (2 - frame_mbs_only) if chroma_array_type == 0 else sub_c[1] * (2 - frame_mbs_only)
    width = width_mbs - (crop_l + crop_r) * crop_unit_x
    height = (2 - frame_mbs_only) * height_maps - (crop_t + crop_b) * crop_unit_y
    return width, height


def decode_bytes(data):
    return parse_sps(find_sps(data))


def load_input(token):
    token = token.strip()
    if token.startswith("@"):  # file path (Annex-B or raw)
        with open(token[1:], "rb") as fh:
            return fh.read()
    try:
        return binascii.unhexlify(token)
    except (binascii.Error, ValueError):
        pass
    try:
        return base64.b64decode(token + "=" * (-len(token) % 4))
    except (binascii.Error, ValueError):
        pass
    raise ValueError("input is neither hex nor base64 (or use @file for Annex-B)")


class _BitWriter:
    def __init__(self):
        self.bits = []

    def u(self, n, v):
        for i in range(n - 1, -1, -1):
            self.bits.append((v >> i) & 1)

    def ue(self, v):
        k = v + 1
        n = k.bit_length() - 1
        self.u(n, 0)
        self.u(n + 1, k)

    def bytes(self):
        out = bytearray()
        for i in range(0, len(self.bits), 8):
            chunk = self.bits[i : i + 8] + [0] * (8 - len(self.bits[i : i + 8]))
            b = 0
            for bit in chunk:
                b = (b << 1) | bit
            out.append(b)
        return bytes(out)


def _build_sps(profile_idc, width_mbs, height_mbs, crop=None, chroma_idc=None):
    w = _BitWriter()
    w.u(8, 0x67)  # NAL header: forbidden=0, ref_idc=3, type=7 (SPS)
    w.u(8, profile_idc)
    w.u(8, 0)  # constraint flags
    w.u(8, 40)  # level 4.0
    w.ue(0)  # sps id
    if profile_idc == 100:
        w.ue(chroma_idc if chroma_idc is not None else 1)
        w.ue(0)  # bit depth luma
        w.ue(0)  # bit depth chroma
        w.u(1, 0)  # qpprime bypass
        w.u(1, 0)  # scaling matrix
    w.ue(0)  # log2_max_frame_num_minus4
    w.ue(0)  # poc type 0
    w.ue(0)  # log2_max_poc_lsb_minus4
    w.ue(1)  # max_num_ref_frames
    w.u(1, 0)  # gaps
    w.ue(width_mbs // 16 - 1)  # pic_width_in_mbs_minus1 (arg is coded px)
    w.ue(height_mbs // 16 - 1)  # pic_height_in_map_units_minus1
    w.u(1, 1)  # frame_mbs_only
    w.u(1, 1)  # direct_8x8
    if crop:
        w.u(1, 1)
        for v in crop:
            w.ue(v)
    else:
        w.u(1, 0)
    w.u(1, 0)  # vui
    return w.bytes()


def _selftest():
    cases = [
        # (name, sps bytes, expected (w, h))
        ("baseline 1280x720, no crop", _build_sps(66, 1280, 720), (1280, 720)),
        # the classic MediaCodec/x264 1080p shape: coded 1088 high,
        # crop bottom 4 (CropUnitY=2 for 4:2:0 progressive)
        ("high 1920x1080 (1088 coded, crop b=4)", _build_sps(100, 1920, 1088, crop=(0, 0, 0, 4)), (1920, 1080)),
        # 4:2:0 CropUnitX=2: two units cropped each side -> 640 - 8
        ("baseline 632x480 (crop l=r=2)", _build_sps(66, 640, 480, crop=(2, 2, 0, 0)), (632, 480)),
        # portrait 1080x1920 as the device emits it: coded width 1088,
        # right-cropped to 1080
        ("baseline 1080x1920 (1088 coded, crop r=4)", _build_sps(66, 1088, 1920, crop=(0, 4, 0, 0)), (1080, 1920)),
    ]
    failed = 0
    for name, sps, want in cases:
        got = decode_bytes(sps)
        ok = got == want
        failed += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: got {got[0]}x{got[1]}, want {want[0]}x{want[1]}")
    # start-code + emulation-prevention round trips: force a 00 00 pair into
    # the constraint/level bytes (parser ignores their values), then escape
    # it the way a real muxer must (00 00 0X -> 00 00 03 0X)
    sps_z = cases[0][1][:2] + b"\x00\x00" + cases[0][1][4:]
    escaped = sps_z[:2] + b"\x00\x00\x03" + sps_z[4:]
    mech = [
        ("annex-b scan", b"\x00\x00\x00\x01" + cases[0][1], cases[0][2]),
        ("emulation escape strips to original", escaped, (1280, 720)),
        ("unescaped 00 00 also decodes", sps_z, (1280, 720)),
    ]
    for name, data, want in mech:
        got = decode_bytes(data)
        ok = got == want and strip_emulation(escaped) == sps_z
        failed += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: {got[0]}x{got[1]}")
    return 1 if failed else 0


def main(argv):
    if "--selftest" in argv:
        sys.exit(_selftest())
    token = argv[1] if len(argv) > 1 else sys.stdin.read()
    w, h = decode_bytes(load_input(token))
    print(f"{w}x{h}")


if __name__ == "__main__":
    main(sys.argv)
