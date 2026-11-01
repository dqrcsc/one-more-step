package com.oms.client.agent

object SensitiveActionPolicy {
    private const val SELF_PACKAGE = "com.oms.client"

    val SENSITIVE_KEYWORDS = listOf(
        "提交", "发送", "发布", "删除", "付款", "支付", "转账", "红包", "打款", "提现", "充值",
        "购买", "下单", "结算", "扣款", "免密", "卸载", "清除数据", "格式化", "注销", "授权", "允许",
        "确认转账", "立即支付", "确认删除",
        "pay", "transfer", "checkout", "purchase", "confirm", "delete", "uninstall", "remove",
        "allow", "authorize", "grant", "send", "submit"
    )

    private val BLOCKED_FINANCIAL_PACKAGES = setOf(
        "com.eg.android.AlipayGphone", // Alipay
        "com.unionpay",                // UnionPay
        "com.icbc",                    // ICBC
        "com.chinamworld.main",        // CCB
        "cmb.pb",                      // CMB
        "com.android.bankabc",         // ABC
        "com.chinamworld.bocmbci",     // BOC
        "com.bankcomm.Bankcomm",       // BoCom
        "com.psbc.mobilebank",         // PSBC
        "com.pingan.paces.ccms",       // PingAn Pocket Bank
        "com.citiccard.mobilebank",    // CITIC
        "com.cebbank.mobile.cemb",     // CEB
        "com.cmbc.cc.mbank",           // CMBC
        "cn.com.spdb.mobilebank.per",  // SPDB
        "com.cib.cibmb",               // CIB
        "com.mipay.wallet",            // Xiaomi Wallet / MiPay
        "com.finshell.wallet",         // OPPO/OnePlus/Realme Wallet
        "com.huawei.wallet",           // Huawei Wallet
        "com.paypal.android.p2pmobile",// PayPal
        "com.stripe.android.dashboard",// Stripe
        "com.binance.dev",             // Binance
        "com.okinc.okex.gp",           // OKX
        "io.metamask",                 // MetaMask
        "com.wallet.crypto.trustapp",  // Trust Wallet
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.miui.securitycenter"
    )

    private val SENSITIVE_ACTIVITY_TOKENS = listOf(
        "pay", "password", "wallet", "cashier", "biometric", "fingerprint", "keyguard",
        "accessibility", "deviceadmin", "notificationaccess", "specialaccess", "permission"
    )

    fun isBlockedPackage(packageName: String?, allowSelfPackage: Boolean = false): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (pkg == SELF_PACKAGE) return !allowSelfPackage
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
        hasFocusedPasswordInput: Boolean,
        allowSelfPackage: Boolean = false
    ): Boolean {
        if (hasFocusedPasswordInput) return true
        if (isBlockedPackage(currentPackageName, allowSelfPackage = allowSelfPackage)) return true
        if (isBlockedActivity(currentActivityName)) return true
        return rootPackages.any { isBlockedPackage(it, allowSelfPackage = allowSelfPackage) }
    }

    fun requiresObservationId(action: String, targetNodeId: String, hasCoordinates: Boolean): Boolean {
        val normalized = action.trim().lowercase()
        return targetNodeId.isNotBlank() || (normalized in setOf("click", "long_click", "swipe") && hasCoordinates)
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
        return normalized in setOf("click", "long_click", "swipe") && hitsSensitiveKeyword(resolvedLabel)
    }
}
