package com.oms.client.capability

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Lightweight zero-dependency Biometric / Device-Credential Authenticator (API 30+).
 * Used for Policy B: Remote WAN Mac Unlock requires phone fingerprint/face/PIN confirmation,
 * while local LAN_WS unlocks immediately in 1 tap.
 */
object BiometricAuthHelper {

    suspend fun authenticateForSensitiveRemoteAction(
        context: Context,
        title: String = "验证身份以解锁 Mac",
        subtitle: String = "当前处于跨网远程连接，请验证指纹、面容或锁屏密码"
    ): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val bm = context.getSystemService(BiometricManager::class.java)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (bm != null && bm.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            return@withContext false to "当前手机未启用指纹/面容或锁屏密码，无法完成远程安全核验"
        }

        suspendCancellableCoroutine { cont ->
            val cancelSignal = CancellationSignal()
            cont.invokeOnCancellation { cancelSignal.cancel() }

            runCatching {
                val prompt = BiometricPrompt.Builder(context)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(allowed)
                    .build()

                prompt.authenticate(
                    cancelSignal,
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            if (cont.isActive) cont.resume(true to "验证通过")
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            if (cont.isActive) cont.resume(false to (errString?.toString() ?: "已取消身份验证"))
                        }

                        override fun onAuthenticationFailed() {
                            // System UI stays open for retry until user cancels or succeeds
                        }
                    }
                )
            }.onFailure { err ->
                if (cont.isActive) cont.resume(false to "唤起生物识别失败：${err.message}")
            }
        }
    }
}
