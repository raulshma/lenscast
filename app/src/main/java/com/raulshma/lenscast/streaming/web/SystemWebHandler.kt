package com.raulshma.lenscast.streaming.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.streaming.model.BatteryDetailDto
import com.raulshma.lenscast.streaming.model.StorageInfoDto
import com.raulshma.lenscast.streaming.model.SystemInfoResponseDto

/**
 * /api/system — the read-only diagnostics snapshot for a headless phone:
 * build/device identity, uptimes, and the battery/storage facts the status
 * endpoint deliberately leaves coarse. Every read degrades to a safe value —
 * a missing sticky battery intent or an unavailable volume stat must never
 * 500 the triage page.
 */
class SystemWebHandler(
    private val context: Context,
    private val powerManager: PowerManager,
    private val captureHistoryStore: CaptureHistoryStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(SystemInfoResponseDto::class.java) }

    fun get(): String {
        val response = SystemInfoResponseDto(
            appVersion = appVersion(),
            deviceModel = Build.MODEL ?: "",
            deviceManufacturer = Build.MANUFACTURER ?: "",
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            osUptimeMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L),
            processUptimeMs = processUptimeMs(),
            battery = batteryDetail(),
            storage = storageInfo(),
        )
        return responseAdapter.toJson(response)
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Ms since this process started; 0 below API 24 (no start stamp exists). */
    private fun processUptimeMs(): Long = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (nowMs() - Process.getStartUptimeMillis()).coerceAtLeast(0L)
        } else {
            0L
        }
    }.getOrDefault(0L)

    /**
     * The battery's extended facts ride the BATTERY_CHANGED sticky intent —
     * the platform's one source for temperature, voltage, and health. Absent
     * extras stay null; the level/charging pair comes from the PowerManager
     * so the two endpoints can never disagree.
     */
    private fun batteryDetail(): BatteryDetailDto {
        val intent: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        fun intExtra(name: String): Int? = intent?.takeIf { it.hasExtra(name) }?.getIntExtra(name, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return BatteryDetailDto(
            level = powerManager.batteryLevel.value ?: PowerManager.UNKNOWN_BATTERY_FALLBACK_PERCENT,
            isCharging = powerManager.isChargingNow(),
            temperatureTenthsC = intExtra(BatteryManager.EXTRA_TEMPERATURE),
            voltageMillivolts = intExtra(BatteryManager.EXTRA_VOLTAGE),
            health = batteryHealthName(intExtra(BatteryManager.EXTRA_HEALTH)),
        )
    }

    private fun batteryHealthName(health: Int?): String? = when (health) {
        null -> null
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown"
    }

    /** App-volume stats through StatFs; a failed stat reports zeros, not an error. */
    private fun storageInfo(): StorageInfoDto = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        StorageInfoDto(
            usedBytes = captureHistoryStore.totalBytes(),
            quotaBytes = captureHistoryStore.quotaBytes(),
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.getOrDefault(
        StorageInfoDto(
            usedBytes = captureHistoryStore.totalBytes(),
            quotaBytes = captureHistoryStore.quotaBytes(),
            freeBytes = 0L,
            totalBytes = 0L,
        ),
    )
}
