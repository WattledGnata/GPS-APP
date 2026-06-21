// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.blazepush.core.network.DiagnosticUploadMeta

/**
 * 采集诊断上传元数据（design Decision 7）。
 * framework 取值（设备型号 / Android ID / 版本）由 [collect] 完成（真机验证）；
 * ticket 归一化等组装逻辑抽到 [build] 纯函数，可单测。
 */
object DiagnosticMetadataCollector {

    fun collect(context: Context, ticket: String?, nowMs: Long): DiagnosticUploadMeta {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return build(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidId = androidId,
            versionName = pkg.versionName ?: "",
            versionCode = PackageInfoCompat.getLongVersionCode(pkg).toString(),
            nowMs = nowMs,
            ticket = ticket,
        )
    }

    /** 纯组装：空白 ticket 归一化为 null、非空 trim 保留（可单测）。 */
    internal fun build(
        deviceModel: String,
        androidId: String,
        versionName: String,
        versionCode: String,
        nowMs: Long,
        ticket: String?,
    ): DiagnosticUploadMeta = DiagnosticUploadMeta(
        deviceModel = deviceModel,
        androidId = androidId,
        versionName = versionName,
        versionCode = versionCode,
        capturedAtMs = nowMs,
        ticket = ticket?.trim()?.takeIf { it.isNotBlank() },
    )
}
