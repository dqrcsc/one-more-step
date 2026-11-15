package com.oms.client.transport

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads privately and never hands an unverified URL or APK to an external app. */
class VerifiedOtaInstaller(private val context: Context) {
    private val installing = AtomicBoolean(false)

    suspend fun install(metadata: VerifiedOtaPolicy.Metadata, client: OkHttpClient) {
        check(installing.compareAndSet(false, true)) { "已有更新正在下载，请稍候" }
        var partial: File? = null
        try {
            metadata.validate()
            check(context.packageManager.canRequestPackageInstalls()) {
                "请在系统设置 → 应用 → 特殊应用权限 → 安装未知应用中允许青简·咫尺，然后重新点击更新"
            }
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "verified_ota").apply { mkdirs() }
                // One bounded file is kept for the installer; replacing it is safe only after the previous user flow ends.
                val target = File(dir, "update.apk")
                val part = File(dir, "${UUID.randomUUID()}.part.apk")
                partial = part
                val downloadClient = client.newBuilder().callTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
                downloadClient.newCall(Request.Builder().url(metadata.url).get().build()).execute().use { response ->
                    check(response.isSuccessful) { "APK 下载失败：HTTP ${response.code}" }
                    val body = checkNotNull(response.body) { "APK 下载内容为空" }
                    val length = body.contentLength()
                    require(length <= VerifiedOtaPolicy.MAX_APK_BYTES &&
                        (length < 0 || metadata.size == 0L || length == metadata.size)) { "APK 下载长度与预期不符" }
                    body.byteStream().use { input -> FileOutputStream(part).use { output ->
                        VerifiedOtaPolicy.copyAndVerify(input, output, metadata)
                        output.fd.sync()
                    } }
                }
                val pm = context.packageManager
                val apk = checkNotNull(pm.getPackageArchiveInfo(part.path, PackageManager.GET_SIGNING_CERTIFICATES)) { "无法读取 APK 签名或包信息" }
                val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                VerifiedOtaPolicy.validatePackage(metadata, apk.packageName, context.packageName,
                    apk.versionName, apk.longVersionCode, installed.versionName, installed.longVersionCode,
                    signers(apk), signers(installed))
                // Atomic replacement prevents exposing a partially written package through FileProvider.
                check(part.renameTo(target)) { "无法保存已验证 APK" }
                partial = null
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota", target)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        } finally {
            partial?.delete()
            installing.set(false)
        }
    }

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
        ?.map { OmsFrameProtocol.sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
}
