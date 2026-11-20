package com.oms.client.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/** Expected digests are accepted only from authenticated RPC or the HTTPS GitHub release API. */
object VerifiedOtaPolicy {
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val MAX_REDIRECT_HOPS = 5
    enum class Source { AUTHENTICATED_RPC, GITHUB_HTTPS }

    fun isTrustedGithubDownloadHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h == "githubusercontent.com" || h.endsWith(".githubusercontent.com")
    }

    data class Metadata(val url: String, val sha256: String, val size: Long,
                        val versionName: String, val versionCode: Long, val source: Source?) {
        fun validate() {
            require(source != null) { "更新缺少可信来源，不能安装" }
            require(Regex("[0-9a-fA-F]{64}").matches(sha256)) { "更新缺少可信 SHA-256，不能安装" }
            val uri = URI(url)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "无效的 APK 下载地址" }
            if (source == Source.GITHUB_HTTPS) require(uri.scheme == "https" && isTrustedGithubDownloadHost(uri.host)) { "GitHub 更新必须使用可信 HTTPS 域名" }
            require(size in 0..MAX_APK_BYTES) { "APK 超过 128 MiB 上限" }
            require(OtaVersionPolicy.parseSemVer(versionName) != null) { "更新版本信息无效" }
        }
    }

    fun validateRedirectTarget(metadata: Metadata, currentUrl: String, locationHeader: String): String {
        metadata.validate()
        require(locationHeader.isNotBlank()) { "重定向缺少目标地址" }
        val currentUri = URI(currentUrl)
        val targetUri = currentUri.resolve(locationHeader)
        require(targetUri.scheme in setOf("http", "https") && !targetUri.host.isNullOrBlank() && targetUri.userInfo == null) {
            "无效的 APK 重定向地址"
        }
        if (currentUri.scheme == "https") {
            require(targetUri.scheme == "https") { "拒绝从 HTTPS 降级到明文 HTTP 下载更新" }
        }
        if (metadata.source == Source.GITHUB_HTTPS || targetUri.scheme == "https") {
            require(targetUri.scheme == "https" && isTrustedGithubDownloadHost(targetUri.host)) {
                "更新重定向仅允许可信 GitHub HTTPS 节点"
            }
        }
        return targetUri.toString()
    }

    fun copyAndVerify(
        input: InputStream,
        output: OutputStream,
        metadata: Metadata,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Long {
        metadata.validate()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        onProgress?.invoke(0L, metadata.size)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= MAX_APK_BYTES && (metadata.size == 0L || total <= metadata.size)) { "APK 下载大小超限" }
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            onProgress?.invoke(total, metadata.size)
        }
        require(total > 0 && (metadata.size == 0L || total == metadata.size)) { "APK 下载不完整" }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(metadata.sha256, true)) { "APK SHA-256 不匹配，已拒绝安装" }
        return total
    }

    fun validatePackage(metadata: Metadata, packageName: String, installedPackage: String,
                        versionName: String?, versionCode: Long, installedName: String?, installedCode: Long,
                        signers: Set<String>, installedSigners: Set<String>) {
        metadata.validate()
        require(packageName == installedPackage) { "APK 包名不匹配" }
        require(versionName == metadata.versionName) { "APK 版本与更新元数据不匹配" }
        require(metadata.versionCode == 0L || versionCode == metadata.versionCode) { "APK 构建号与更新元数据不匹配" }
        require(versionCode >= installedCode && (OtaVersionPolicy.compareSemVer(versionName, installedName) ?: -1) >= 0) { "拒绝安装旧版本 APK" }
        require(signers.isNotEmpty() && signers == installedSigners) { "APK 签名与已安装应用不一致" }
    }
}
