package com.oms.client.capability

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.oms.client.agent.OmsAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppLauncherHelper {

    data class LaunchResult(
        val ok: Boolean,
        val packageName: String,
        val appLabel: String,
        val screenLocked: Boolean,
        val error: String? = null
    )

    private val KNOWN_LABELS = mapOf(
        "com.oms.client" to "青简·咫尺 (OneMoreStep)",
        "com.miui.home" to "小米系统桌面",
        "com.android.systemui" to "系统状态栏 / 锁屏",
        "com.android.settings" to "系统设置",
        "com.tencent.mm" to "微信",
        "com.ss.android.lark" to "飞书",
        "com.larksuite.suite" to "飞书",
        "com.alibaba.android.rimet" to "钉钉",
        "com.tencent.mobileqq" to "QQ",
        "com.xingin.xhs" to "小红书",
        "com.ss.android.ugc.aweme" to "抖音",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.taobao.taobao" to "淘宝",
        "com.jingdong.app.mall" to "京东",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.taobao.idlefish" to "闲鱼",
        "com.sankuai.meituan" to "美团",
        "me.ele" to "饿了么",
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.sdu.didi.psnger" to "滴滴出行",
        "com.MobileTicket" to "铁路12306",
        "ctrip.android.view" to "携程旅行",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
        "com.zhihu.android" to "知乎",
        "com.sina.weibo" to "微博",
        "com.douban.frodo" to "豆瓣",
        "cn.wps.moffice_eng" to "WPS Office",
        "com.quark.browser" to "夸克",
        "com.larus.nova" to "豆包",
        "com.moonshot.kimichat" to "Kimi",
        "com.tencent.hunyuan.app.chat" to "腾讯元宝",
        "com.xiaomi.smarthome" to "米家",
        "com.xiaomi.market" to "小米应用商店",
        "com.android.camera" to "小米相机",
        "com.miui.camera" to "小米相机",
        "com.miui.gallery" to "小米相册",
        "com.miui.notes" to "小米笔记",
        "com.android.deskclock" to "系统时钟",
        "com.android.calendar" to "小米日历",
        "com.miui.calendar" to "小米日历",
        "com.miui.calculator" to "小米计算器",
        "com.android.fileexplorer" to "文件管理",
        "com.mi.android.globalFileexplorer" to "文件管理",
        "com.android.soundrecorder" to "录音机",
        "com.miui.weather2" to "小米天气",
        "com.android.contacts" to "电话 / 联系人",
        "com.android.mms" to "短信",
        "com.miui.securitycenter" to "小米手机管家",
        "com.android.browser" to "小米浏览器",
        "com.mi.globalbrowser" to "小米浏览器"
    )

    fun resolveFriendlyAppName(context: Context, pkg: String): String {
        if (pkg.isBlank()) return "桌面 / 待机"
        KNOWN_LABELS[pkg]?.let { return it }
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
        } catch (_: Throwable) {
            pkg
        }
    }

    private fun resolveCandidatePackages(context: Context, input: String): List<String> {
        val key = input.trim().lowercase()
        return when (key) {
            "wechat", "weixin", "微信" -> listOf("com.tencent.mm")
            "lark", "feishu", "飞书" -> listOf("com.ss.android.lark", "com.larksuite.suite")
            "dingtalk", "钉钉" -> listOf("com.alibaba.android.rimet")
            "qq" -> listOf("com.tencent.mobileqq")
            "xhs", "xiaohongshu", "rednote", "小红书" -> listOf("com.xingin.xhs")
            "douyin", "tiktok", "抖音" -> listOf("com.ss.android.ugc.aweme")
            "bilibili", "bili", "b站", "哔哩哔哩" -> listOf("tv.danmaku.bili")
            "alipay", "支付宝" -> listOf("com.eg.android.AlipayGphone")
            "taobao", "淘宝" -> listOf("com.taobao.taobao")
            "jd", "jingdong", "京东" -> listOf("com.jingdong.app.mall")
            "pdd", "pinduoduo", "拼多多" -> listOf("com.xunmeng.pinduoduo")
            "xianyu", "闲鱼" -> listOf("com.taobao.idlefish")
            "meituan", "美团" -> listOf("com.sankuai.meituan")
            "eleme", "饿了么" -> listOf("me.ele")
            "amap", "gaode", "高德", "高德地图" -> listOf("com.autonavi.minimap")
            "baidumap", "百度地图" -> listOf("com.baidu.BaiduMap")
            "didi", "滴滴", "滴滴出行" -> listOf("com.sdu.didi.psnger")
            "12306", "铁路12306" -> listOf("com.MobileTicket")
            "ctrip", "携程", "携程旅行" -> listOf("ctrip.android.view")
            "netease", "cloudmusic", "网易云", "网易云音乐" -> listOf("com.netease.cloudmusic")
            "qqmusic", "qq音乐" -> listOf("com.tencent.qqmusic")
            "zhihu", "知乎" -> listOf("com.zhihu.android")
            "weibo", "微博" -> listOf("com.sina.weibo")
            "douban", "豆瓣" -> listOf("com.douban.frodo")
            "wps", "wps office" -> listOf("cn.wps.moffice_eng")
            "quark", "夸克" -> listOf("com.quark.browser")
            "doubao", "豆包" -> listOf("com.larus.nova")
            "kimi" -> listOf("com.moonshot.kimichat")
            "yuanbao", "腾讯元宝", "元宝" -> listOf("com.tencent.hunyuan.app.chat")
            "mihome", "mijia", "米家" -> listOf("com.xiaomi.smarthome")
            "market", "appstore", "应用商店", "小米应用商店" -> listOf("com.xiaomi.market")
            "settings", "设置", "系统设置" -> listOf("com.android.settings")
            "camera", "相机", "拍照", "小米相机" -> listOf("com.android.camera", "com.miui.camera")
            "browser", "浏览器", "小米浏览器" -> listOf("com.android.browser", "com.mi.globalbrowser")
            "clock", "alarm", "时钟", "闹钟" -> listOf("com.android.deskclock")
            "calendar", "日历", "小米日历" -> listOf("com.android.calendar", "com.miui.calendar")
            "gallery", "photos", "相册", "图库", "小米相册" -> listOf("com.miui.gallery")
            "notes", "笔记", "便签", "小米笔记" -> listOf("com.miui.notes")
            "calculator", "计算器" -> listOf("com.miui.calculator")
            "files", "fileexplorer", "文件管理" -> listOf("com.android.fileexplorer", "com.mi.android.globalFileexplorer")
            "recorder", "录音机" -> listOf("com.android.soundrecorder")
            "weather", "天气", "小米天气" -> listOf("com.miui.weather2")
            "phone", "dialer", "contacts", "电话", "拨号", "联系人" -> listOf("com.android.contacts")
            "sms", "messages", "短信", "信息" -> listOf("com.android.mms")
            "security", "手机管家", "小米手机管家" -> listOf("com.miui.securitycenter")
            "oms", "onemorestep", "青简", "青简咫尺" -> listOf(context.packageName)
            else -> if (input.contains(".")) listOf(input.trim()) else emptyList()
        }
    }

    private fun buildIntentForPackage(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        try {
            pm.getLaunchIntentForPackage(pkg)?.let { return it }
        } catch (_: Throwable) {
        }

        try {
            val query = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
            }
            val list = pm.queryIntentActivities(query, 0)
            if (list.isNotEmpty()) {
                val ai = list[0].activityInfo
                return Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(ai.packageName, ai.name)
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val query = Intent(Intent.ACTION_MAIN, null).apply {
                setPackage(pkg)
            }
            val list = pm.queryIntentActivities(query, 0)
            if (list.isNotEmpty()) {
                val ai = list[0].activityInfo
                return Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(ai.packageName, ai.name)
                }
            }
        } catch (_: Throwable) {
        }

        return when (pkg) {
            "com.android.settings" -> Intent(Settings.ACTION_SETTINGS)
            "com.android.camera", "com.miui.camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "com.android.deskclock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            else -> null
        }
    }

    fun wakeScreenIfNeeded(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "oms:remote_launch_wakeup"
                )
                wl.acquire(3500L)
            }
        } catch (_: Throwable) {
        }
    }

    suspend fun launchApp(context: Context, rawTarget: String): LaunchResult {
        val query = rawTarget.trim()
        if (query.isEmpty()) {
            return LaunchResult(false, "", "", false, "未指定要打开的应用名称或包名")
        }

        val pm = context.packageManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = km.isKeyguardLocked

        var resolvedPkg = ""
        var resolvedLabel = ""
        var targetIntent: Intent? = null

        // 1. Try candidate packages from alias map or direct package name
        val candidates = resolveCandidatePackages(context, query)
        for (pkg in candidates) {
            val intent = buildIntentForPackage(context, pkg)
            if (intent != null) {
                targetIntent = intent
                resolvedPkg = intent.component?.packageName ?: intent.`package` ?: pkg
                resolvedLabel = resolveFriendlyAppName(context, resolvedPkg)
                break
            }
        }

        // 2. Dynamic scan across all installed launcher activities by label / package
        if (targetIntent == null) {
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val launchables = pm.queryIntentActivities(mainIntent, 0)
                var matchedInfo = launchables.firstOrNull { ri ->
                    val pkg = ri.activityInfo.packageName
                    val label = ri.loadLabel(pm)?.toString()?.trim() ?: ""
                    pkg.equals(query, ignoreCase = true) || label.equals(query, ignoreCase = true)
                }
                if (matchedInfo == null) {
                    matchedInfo = launchables.firstOrNull { ri ->
                        val label = ri.loadLabel(pm)?.toString()?.trim() ?: ""
                        label.isNotEmpty() && (
                            label.contains(query, ignoreCase = true) ||
                                (label.length >= 2 && query.contains(label, ignoreCase = true))
                            )
                    }
                }
                if (matchedInfo != null) {
                    val ai = matchedInfo.activityInfo
                    resolvedPkg = ai.packageName
                    resolvedLabel = matchedInfo.loadLabel(pm)?.toString()?.trim().orEmpty()
                        .ifBlank { resolveFriendlyAppName(context, resolvedPkg) }
                    targetIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(ai.packageName, ai.name)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (targetIntent == null) {
            return LaunchResult(
                ok = false,
                packageName = candidates.firstOrNull() ?: query,
                appLabel = query,
                screenLocked = isLocked,
                error = "未在手机上找到已安装的应用「$query」"
            )
        }

        return withContext(Dispatchers.Main) {
            try {
                wakeScreenIfNeeded(context)
                val svc = OmsAccessibilityService.instance
                if (svc != null && Build.VERSION.SDK_INT >= 31 && !isLocked) {
                    try {
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    } catch (_: Throwable) {
                    }
                }
                targetIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                val launcherCtx: Context = svc ?: context
                try {
                    launcherCtx.startActivity(targetIntent)
                } catch (_: Throwable) {
                    context.startActivity(targetIntent)
                }
                LaunchResult(
                    ok = true,
                    packageName = resolvedPkg,
                    appLabel = resolvedLabel.ifBlank { resolvedPkg },
                    screenLocked = isLocked,
                    error = null
                )
            } catch (e: Throwable) {
                LaunchResult(
                    ok = false,
                    packageName = resolvedPkg,
                    appLabel = resolvedLabel.ifBlank { resolvedPkg },
                    screenLocked = isLocked,
                    error = "启动应用失败: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    suspend fun openUrl(context: Context, rawUrl: String): Pair<Boolean, String?> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false to "URL 不能为空"
        val normalizedUrl = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withContext(Dispatchers.Main) {
            try {
                wakeScreenIfNeeded(context)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launcherCtx: Context = OmsAccessibilityService.instance ?: context
                launcherCtx.startActivity(intent)
                true to null
            } catch (e: Throwable) {
                false to (e.message ?: "无法打开该链接")
            }
        }
    }
}
