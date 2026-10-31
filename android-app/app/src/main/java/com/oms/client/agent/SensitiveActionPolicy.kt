package com.oms.client.agent

object SensitiveActionPolicy {
    val SENSITIVE_KEYWORDS = listOf(
        "提交", "发送", "发布", "删除", "付款", "转账", "卸载", "确认转账", "立即支付", "确认删除",
        "confirm", "delete", "uninstall", "allow"
    )

    private val BLOCKED_FINANCIAL_PACKAGES = setOf(
        "com.oms.client",              // Self-protection: prevent AI from self-approving or re-pairing
        "com.eg.android.AlipayGphone", // Alipay
        "com.unionpay",                // UnionPay
        "com.icbc",
        "com.chinamworld.main",
        "cmb.pb",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.miui.securitycenter"
    )

    private val SENSITIVE_ACTIVITY_TOKENS = listOf(
        "pay", "password", "wallet", "accessibility", "deviceadmin", "notificationaccess", "specialaccess", "biometric"
    )

    fun isBlockedPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        return pkg in BLOCKED_FINANCIAL_PACKAGES
    }

    fun isBlockedActivity(activityName: String?): Boolean {
        val actLower = activityName?.trim()?.lowercase().orEmpty()
        if (actLower.isEmpty()) return false
        return SENSITIVE_ACTIVITY_TOKENS.any { token -> actLower.contains(token) }
    }

    fun isBlockedWindowSet(
        currentPackageName: String?,
        currentActivityName: String?,
        rootPackages: Iterable<String?>,
        hasFocusedPasswordInput: Boolean
    ): Boolean {
        if (hasFocusedPasswordInput) return true
        if (isBlockedPackage(currentPackageName)) return true
        if (isBlockedActivity(currentActivityName)) return true
        return rootPackages.any { isBlockedPackage(it) }
    }

    fun requiresObservationId(action: String, targetNodeId: String, hasCoordinates: Boolean): Boolean {
        val normalized = action.trim().lowercase()
        return targetNodeId.isNotBlank() || (normalized in setOf("click", "long_click") && hasCoordinates)
    }

    fun hitsSensitiveKeyword(label: String): Boolean {
        val trimmed = label.trim().lowercase()
        if (trimmed.isEmpty()) return false
        return SENSITIVE_KEYWORDS.any { kw -> trimmed.contains(kw) }
    }

    fun requiresUserConfirmation(
        action: String,
        explicitRequireConfirm: Boolean,
        resolvedLabel: String
    ): Boolean {
        if (explicitRequireConfirm) return true
        val normalized = action.trim().lowercase()
        return normalized in setOf("click", "long_click") && hitsSensitiveKeyword(resolvedLabel)
    }
}
