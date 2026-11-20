package com.oms.client.transport

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class OtaUiState {
    data object Idle : OtaUiState()
    data class Checking(val silent: Boolean) : OtaUiState()
    data class UpdateAvailable(val ota: OtaCheckResult) : OtaUiState()
    data class Downloading(
        val ota: OtaCheckResult,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int
    ) : OtaUiState()
    data class ReadyToInstall(val ota: OtaCheckResult, val apkFile: File) : OtaUiState()
    data class NeedsInstallPermission(val ota: OtaCheckResult, val apkFile: File) : OtaUiState()
    data class InstallerLaunched(val ota: OtaCheckResult, val apkFile: File) : OtaUiState()
    data class Error(val message: String, val ota: OtaCheckResult? = null) : OtaUiState()
}

/** Downloads privately, supports verified background pre-download, and never hands an unverified APK to an external app. */
class VerifiedOtaInstaller(private val context: Context) {
    private val prepareMutex = Mutex()

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun prepareVerifiedApk(
        metadata: VerifiedOtaPolicy.Metadata,
        client: OkHttpClient,
        fallbackHttpsClient: OkHttpClient = client,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = prepareMutex.withLock {
        metadata.validate()
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "verified_ota").apply { mkdirs() }
            val safeKey = metadata.sha256.lowercase().take(16)
            val target = File(dir, "update_$safeKey.apk")

            // Fast path: reuse already downloaded & cryptographically verified APK in local cache
            if (target.exists() && target.isFile && target.length() > 0L) {
                val cachedValid = runCatching {
                    target.inputStream().use { input ->
                        VerifiedOtaPolicy.copyAndVerify(input, NullOutputStream, metadata)
                    }
                    verifyArchiveIdentity(target, metadata)
                    true
                }.getOrDefault(false)
                if (cachedValid) {
                    onProgress(target.length(), target.length())
                    pruneOtherCachedApks(dir, keepName = target.name)
                    return@withContext target
                } else {
                    target.delete()
                }
            }

            val part = File(dir, "${UUID.randomUUID()}.part.apk")
            var committed = false
            try {
                executeFollowingValidatedRedirects(metadata, client, fallbackHttpsClient).use { response ->
                    check(response.isSuccessful) { "APK 下载失败：HTTP ${response.code}" }
                    val body = checkNotNull(response.body) { "APK 下载内容为空" }
                    val length = body.contentLength()
                    require(
                        length <= VerifiedOtaPolicy.MAX_APK_BYTES &&
                            (length < 0 || metadata.size == 0L || length == metadata.size)
                    ) { "APK 下载长度与预期不符" }
                    val effectiveTotal = if (metadata.size > 0L) metadata.size else length.coerceAtLeast(0L)
                    var lastEmitMs = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { output ->
                            VerifiedOtaPolicy.copyAndVerify(input, output, metadata) { dl, _ ->
                                val now = System.currentTimeMillis()
                                val pct = if (effectiveTotal > 0L) ((dl * 100L) / effectiveTotal).toInt().coerceIn(0, 100) else 0
                                if (dl == effectiveTotal || pct != lastPct || now - lastEmitMs >= 120L) {
                                    lastEmitMs = now
                                    lastPct = pct
                                    onProgress(dl, effectiveTotal)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }
                verifyArchiveIdentity(part, metadata)
                if (target.exists()) target.delete()
                check(part.renameTo(target)) { "无法保存已验证 APK" }
                committed = true
                pruneOtherCachedApks(dir, keepName = target.name)
                target
            } finally {
                if (!committed) part.delete()
            }
        }
    }

    suspend fun launchSystemInstaller(apkFile: File): Boolean {
        if (!canRequestPackageInstalls()) return false
        check(apkFile.exists() && apkFile.isFile && apkFile.length() > 0L) { "安装包文件不存在，请重新下载" }
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota", apkFile)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        return true
    }

    suspend fun install(
        metadata: VerifiedOtaPolicy.Metadata,
        client: OkHttpClient,
        fallbackHttpsClient: OkHttpClient = client,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val verifiedApk = prepareVerifiedApk(metadata, client, fallbackHttpsClient, onProgress)
        check(launchSystemInstaller(verifiedApk)) {
            "安装包已下载并验签就绪；请在系统设置 → 安装未知应用中允许青简·咫尺后点击安装"
        }
    }

    private fun executeFollowingValidatedRedirects(
        metadata: VerifiedOtaPolicy.Metadata,
        initialClient: OkHttpClient,
        httpsClient: OkHttpClient
    ): Response {
        var currentUrl = metadata.url
        for (hop in 0..VerifiedOtaPolicy.MAX_REDIRECT_HOPS) {
            val base = if (currentUrl.startsWith("https://", ignoreCase = true)) httpsClient else initialClient
            val hopClient = base.newBuilder()
                .callTimeout(150, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val response = hopClient.newCall(Request.Builder().url(currentUrl).get().build()).execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val loc = response.header("Location")
                response.close()
                check(!loc.isNullOrBlank()) { "APK 下载重定向缺少 Location" }
                check(hop < VerifiedOtaPolicy.MAX_REDIRECT_HOPS) { "APK 下载重定向次数过多" }
                currentUrl = VerifiedOtaPolicy.validateRedirectTarget(metadata, currentUrl, loc)
                continue
            }
            return response
        }
        throw IllegalStateException("APK 下载重定向次数过多")
    }

    private fun verifyArchiveIdentity(file: File, metadata: VerifiedOtaPolicy.Metadata) {
        val pm = context.packageManager
        val apk = checkNotNull(
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        ) { "无法读取 APK 签名或包信息" }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        VerifiedOtaPolicy.validatePackage(
            metadata,
            apk.packageName,
            context.packageName,
            apk.versionName,
            apk.longVersionCode,
            installed.versionName,
            installed.longVersionCode,
            signers(apk),
            signers(installed)
        )
    }

    private fun pruneOtherCachedApks(dir: File, keepName: String) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name != keepName) {
                    f.delete()
                }
            }
        }
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
        ?.map { OmsFrameProtocol.sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
}
