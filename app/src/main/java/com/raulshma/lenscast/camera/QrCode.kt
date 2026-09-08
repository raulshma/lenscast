package com.raulshma.lenscast.camera

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR bitmap for the connect sheet: URL text in, Bitmap out.
 * Pure zxing wrapper so the sheet previews without network.
 */
object QrCode {
    fun render(text: String, sizePx: Int = 512): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
