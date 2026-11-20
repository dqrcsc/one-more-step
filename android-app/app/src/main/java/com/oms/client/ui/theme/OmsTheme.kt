package com.oms.client.ui.theme

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class OmsAppearanceMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "素昼浅色"),
    DARK("dark", "玄夜深色");

    companion object {
        fun fromKey(k: String?): OmsAppearanceMode =
            entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}

enum class OmsThemeStyle(
    val key: String,
    val title: String,
    val subtitle: String,
    val swatchLight: Color,
    val swatchDark: Color,
    val swatchAccent: Color
) {
    QINGJIAN_INK(
        key = "qingjian_ink",
        title = "素笺 · 玄墨",
        subtitle = "东方温润宣纸暖白与纯净 OLED 曜黑，竹青微点缀",
        swatchLight = Color(0xFFFAF9F5),
        swatchDark = Color(0xFF0B0B0D),
        swatchAccent = Color(0xFF0F766E)
    ),
    PURE_MONO(
        key = "pure_mono",
        title = "极简 · 纯粹",
        subtitle = "对标 ChatGPT 原生无色相黑白灰阶，极致克制专注",
        swatchLight = Color(0xFFFFFFFF),
        swatchDark = Color(0xFF000000),
        swatchAccent = Color(0xFF171717)
    ),
    GEMINI_AURORA(
        key = "gemini_aurora",
        title = "星云 · 流光",
        subtitle = "对标 Gemini 现代冰透白与深空星辰蓝紫微光",
        swatchLight = Color(0xFFF8FAFD),
        swatchDark = Color(0xFF0A0E17),
        swatchAccent = Color(0xFF4F46E5)
    ),
    BOTANICAL_SAGE(
        key = "botanical_sage",
        title = "松烟 · 苔痕",
        subtitle = "自然亚麻米白与暗夜松林，博物识物与长文护眼首选",
        swatchLight = Color(0xFFF6F8F5),
        swatchDark = Color(0xFF0A100D),
        swatchAccent = Color(0xFF1F6F50)
    ),
    WARM_AMBER(
        key = "warm_amber",
        title = "琥珀 · 暖咖",
        subtitle = "羊皮纸暖米调与黑胡桃深褐，夜间零蓝光刺激",
        swatchLight = Color(0xFFFBF7F0),
        swatchDark = Color(0xFF110D0B),
        swatchAccent = Color(0xFFD97706)
    );

    companion object {
        fun fromKey(k: String?): OmsThemeStyle =
            entries.firstOrNull { it.key == k } ?: QINGJIAN_INK
    }
}

@Immutable
data class OmsColorPalette(
    val isDark: Boolean,
    val canvas: Color,
    val topBar: Color,
    val drawer: Color,
    val composer: Color,
    val cardSurface: Color,
    val subtleSurface: Color,
    val codeSurface: Color,
    val codeHeaderSurface: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val hairline: Color,
    val strongBorder: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val sendButtonBg: Color,
    val sendButtonFg: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color
)

data class OmsThemeSettings(
    val mode: OmsAppearanceMode = OmsAppearanceMode.SYSTEM,
    val style: OmsThemeStyle = OmsThemeStyle.QINGJIAN_INK
)

enum class OmsToastKind {
    INFO,
    SUCCESS,
    WARNING
}

data class OmsToastMessage(
    val id: Long,
    val text: String,
    val kind: OmsToastKind = OmsToastKind.INFO
)

object OmsThemeController {
    private const val PREFS_NAME = "oms_theme_v3"
    private const val KEY_MODE = "appearance_mode"
    private const val KEY_STYLE = "theme_style"

    private val _settingsFlow = MutableStateFlow(OmsThemeSettings())
    val settingsFlow: StateFlow<OmsThemeSettings> = _settingsFlow

    @Volatile
    private var initialized = false

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _settingsFlow.value = OmsThemeSettings(
                mode = OmsAppearanceMode.fromKey(prefs.getString(KEY_MODE, null)),
                style = OmsThemeStyle.fromKey(prefs.getString(KEY_STYLE, null))
            )
            initialized = true
        }
    }

    fun setAppearanceMode(context: Context, mode: OmsAppearanceMode) {
        ensureLoaded(context)
        val next = _settingsFlow.value.copy(mode = mode)
        _settingsFlow.value = next
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.key)
            .apply()
    }

    fun setThemeStyle(context: Context, style: OmsThemeStyle) {
        ensureLoaded(context)
        val next = _settingsFlow.value.copy(style = style)
        _settingsFlow.value = next
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STYLE, style.key)
            .apply()
    }

    fun cycleAppearanceMode(context: Context) {
        ensureLoaded(context)
        val next = when (_settingsFlow.value.mode) {
            OmsAppearanceMode.SYSTEM -> OmsAppearanceMode.LIGHT
            OmsAppearanceMode.LIGHT -> OmsAppearanceMode.DARK
            OmsAppearanceMode.DARK -> OmsAppearanceMode.SYSTEM
        }
        setAppearanceMode(context, next)
    }
}

object OmsFeedbackController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _toastFlow = MutableStateFlow<OmsToastMessage?>(null)
    val toastFlow: StateFlow<OmsToastMessage?> = _toastFlow
    private var dismissJob: Job? = null

    fun show(text: String, kind: OmsToastKind = OmsToastKind.INFO, durationMs: Long = 2200L) {
        val msg = OmsToastMessage(
            id = System.nanoTime(),
            text = text,
            kind = kind
        )
        _toastFlow.value = msg
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(durationMs)
            if (_toastFlow.value?.id == msg.id) {
                _toastFlow.value = null
            }
        }
    }

    fun dismiss() {
        dismissJob?.cancel()
        _toastFlow.value = null
    }
}

fun buildRawPalette(style: OmsThemeStyle, dark: Boolean): OmsColorPalette {
    return when (style) {
        OmsThemeStyle.QINGJIAN_INK -> if (!dark) {
            OmsColorPalette(
                isDark = false,
                canvas = Color(0xFFFAF9F5),
                topBar = Color(0xF2FAF9F5),
                drawer = Color(0xFFF2F0EA),
                composer = Color(0xFFFFFFFF),
                cardSurface = Color(0xFFFFFFFF),
                subtleSurface = Color(0xFFF1EFE8),
                codeSurface = Color(0xFFF3F1EB),
                codeHeaderSurface = Color(0xFFE9E6DE),
                userBubble = Color(0xFFEBE8DF),
                userBubbleText = Color(0xFF171715),
                textPrimary = Color(0xFF171715),
                textSecondary = Color(0xFF595853),
                textTertiary = Color(0xFF8C8A82),
                hairline = Color(0x13000000),
                strongBorder = Color(0x24000000),
                accent = Color(0xFF0F766E),
                accentSoft = Color(0x1A0F766E),
                onAccent = Color(0xFFFFFFFF),
                sendButtonBg = Color(0xFF171715),
                sendButtonFg = Color(0xFFFAF9F5),
                success = Color(0xFF059669),
                successSoft = Color(0x18059669),
                warning = Color(0xFFD97706),
                warningSoft = Color(0x1AD97706),
                danger = Color(0xFFDC2626),
                dangerSoft = Color(0x18DC2626)
            )
        } else {
            OmsColorPalette(
                isDark = true,
                canvas = Color(0xFF0B0B0D),
                topBar = Color(0xF00B0B0D),
                drawer = Color(0xFF121215),
                composer = Color(0xFF1B1B20),
                cardSurface = Color(0xFF151519),
                subtleSurface = Color(0xFF17171C),
                codeSurface = Color(0xFF121216),
                codeHeaderSurface = Color(0xFF1B1B21),
                userBubble = Color(0xFF26262D),
                userBubbleText = Color(0xFFF3F3F6),
                textPrimary = Color(0xFFECECEF),
                textSecondary = Color(0xFF9E9EA8),
                textTertiary = Color(0xFF686873),
                hairline = Color(0x16FFFFFF),
                strongBorder = Color(0x28FFFFFF),
                accent = Color(0xFF2DD4BF),
                accentSoft = Color(0x212DD4BF),
                onAccent = Color(0xFF042F2E),
                sendButtonBg = Color(0xFFF4F4F6),
                sendButtonFg = Color(0xFF0B0B0D),
                success = Color(0xFF34D399),
                successSoft = Color(0x2210B981),
                warning = Color(0xFFFBBF24),
                warningSoft = Color(0x22F59E0B),
                danger = Color(0xFFF87171),
                dangerSoft = Color(0x24EF4444)
            )
        }

        OmsThemeStyle.PURE_MONO -> if (!dark) {
            OmsColorPalette(
                isDark = false,
                canvas = Color(0xFFFFFFFF),
                topBar = Color(0xF5FFFFFF),
                drawer = Color(0xFFF8F8F8),
                composer = Color(0xFFF4F4F4),
                cardSurface = Color(0xFFFAFAFA),
                subtleSurface = Color(0xFFF3F3F3),
                codeSurface = Color(0xFFF6F6F6),
                codeHeaderSurface = Color(0xFFEDEDED),
                userBubble = Color(0xFFEFEFEF),
                userBubbleText = Color(0xFF0D0D0D),
                textPrimary = Color(0xFF0D0D0D),
                textSecondary = Color(0xFF5D5D5D),
                textTertiary = Color(0xFF8E8E8E),
                hairline = Color(0x14000000),
                strongBorder = Color(0x24000000),
                accent = Color(0xFF171717),
                accentSoft = Color(0x12000000),
                onAccent = Color(0xFFFFFFFF),
                sendButtonBg = Color(0xFF0D0D0D),
                sendButtonFg = Color(0xFFFFFFFF),
                success = Color(0xFF10A37F),
                successSoft = Color(0x1810A37F),
                warning = Color(0xFFD97706),
                warningSoft = Color(0x1AD97706),
                danger = Color(0xFFDC2626),
                dangerSoft = Color(0x18DC2626)
            )
        } else {
            OmsColorPalette(
                isDark = true,
                canvas = Color(0xFF000000),
                topBar = Color(0xEB000000),
                drawer = Color(0xFF0F0F0F),
                composer = Color(0xFF1E1E1E),
                cardSurface = Color(0xFF141414),
                subtleSurface = Color(0xFF191919),
                codeSurface = Color(0xFF111111),
                codeHeaderSurface = Color(0xFF1B1B1B),
                userBubble = Color(0xFF2B2B2B),
                userBubbleText = Color(0xFFF5F5F5),
                textPrimary = Color(0xFFECECEC),
                textSecondary = Color(0xFF9B9B9B),
                textTertiary = Color(0xFF686868),
                hairline = Color(0x18FFFFFF),
                strongBorder = Color(0x2BFFFFFF),
                accent = Color(0xFFEAEAEA),
                accentSoft = Color(0x1FFFFFFF),
                onAccent = Color(0xFF0D0D0D),
                sendButtonBg = Color(0xFFFFFFFF),
                sendButtonFg = Color(0xFF000000),
                success = Color(0xFF34D399),
                successSoft = Color(0x2210B981),
                warning = Color(0xFFFBBF24),
                warningSoft = Color(0x22F59E0B),
                danger = Color(0xFFF87171),
                dangerSoft = Color(0x24EF4444)
            )
        }

        OmsThemeStyle.GEMINI_AURORA -> if (!dark) {
            OmsColorPalette(
                isDark = false,
                canvas = Color(0xFFF8FAFD),
                topBar = Color(0xF2F8FAFD),
                drawer = Color(0xFFEEF3FA),
                composer = Color(0xFFFFFFFF),
                cardSurface = Color(0xFFFFFFFF),
                subtleSurface = Color(0xFFEDF2F9),
                codeSurface = Color(0xFFF0F4FA),
                codeHeaderSurface = Color(0xFFE4EAF4),
                userBubble = Color(0xFFE3ECFA),
                userBubbleText = Color(0xFF131E35),
                textPrimary = Color(0xFF131824),
                textSecondary = Color(0xFF525E75),
                textTertiary = Color(0xFF8390A7),
                hairline = Color(0x141E293B),
                strongBorder = Color(0x261E293B),
                accent = Color(0xFF4F46E5),
                accentSoft = Color(0x1A4F46E5),
                onAccent = Color(0xFFFFFFFF),
                sendButtonBg = Color(0xFF3B5BDB),
                sendButtonFg = Color(0xFFFFFFFF),
                success = Color(0xFF059669),
                successSoft = Color(0x18059669),
                warning = Color(0xFFD97706),
                warningSoft = Color(0x1AD97706),
                danger = Color(0xFFDC2626),
                dangerSoft = Color(0x18DC2626)
            )
        } else {
            OmsColorPalette(
                isDark = true,
                canvas = Color(0xFF0A0E17),
                topBar = Color(0xF00A0E17),
                drawer = Color(0xFF101624),
                composer = Color(0xFF182032),
                cardSurface = Color(0xFF121826),
                subtleSurface = Color(0xFF151D2E),
                codeSurface = Color(0xFF0D131F),
                codeHeaderSurface = Color(0xFF162033),
                userBubble = Color(0xFF1F2B45),
                userBubbleText = Color(0xFFEDF2FF),
                textPrimary = Color(0xFFEDF2FA),
                textSecondary = Color(0xFF94A3B8),
                textTertiary = Color(0xFF5F6F87),
                hairline = Color(0x18FFFFFF),
                strongBorder = Color(0x2CFFFFFF),
                accent = Color(0xFF818CF8),
                accentSoft = Color(0x246366F1),
                onAccent = Color(0xFF0F172A),
                sendButtonBg = Color(0xFF6366F1),
                sendButtonFg = Color(0xFFFFFFFF),
                success = Color(0xFF34D399),
                successSoft = Color(0x2210B981),
                warning = Color(0xFFFBBF24),
                warningSoft = Color(0x22F59E0B),
                danger = Color(0xFFF87171),
                dangerSoft = Color(0x24EF4444)
            )
        }

        OmsThemeStyle.BOTANICAL_SAGE -> if (!dark) {
            OmsColorPalette(
                isDark = false,
                canvas = Color(0xFFF6F8F5),
                topBar = Color(0xF2F6F8F5),
                drawer = Color(0xFFECF1EA),
                composer = Color(0xFFFFFFFF),
                cardSurface = Color(0xFFFFFFFF),
                subtleSurface = Color(0xFFEAF0E8),
                codeSurface = Color(0xFFEDF2EB),
                codeHeaderSurface = Color(0xFFE2EAE0),
                userBubble = Color(0xFFDFEADF),
                userBubbleText = Color(0xFF152419),
                textPrimary = Color(0xFF151F18),
                textSecondary = Color(0xFF4E6054),
                textTertiary = Color(0xFF7D8E82),
                hairline = Color(0x140F291A),
                strongBorder = Color(0x260F291A),
                accent = Color(0xFF1F6F50),
                accentSoft = Color(0x1A1F6F50),
                onAccent = Color(0xFFFFFFFF),
                sendButtonBg = Color(0xFF1B4D38),
                sendButtonFg = Color(0xFFF6F8F5),
                success = Color(0xFF059669),
                successSoft = Color(0x18059669),
                warning = Color(0xFFD97706),
                warningSoft = Color(0x1AD97706),
                danger = Color(0xFFDC2626),
                dangerSoft = Color(0x18DC2626)
            )
        } else {
            OmsColorPalette(
                isDark = true,
                canvas = Color(0xFF0A100D),
                topBar = Color(0xF00A100D),
                drawer = Color(0xFF101814),
                composer = Color(0xFF18241E),
                cardSurface = Color(0xFF121C17),
                subtleSurface = Color(0xFF15201B),
                codeSurface = Color(0xFF0E1612),
                codeHeaderSurface = Color(0xFF17241D),
                userBubble = Color(0xFF21332A),
                userBubbleText = Color(0xFFEAF4EE),
                textPrimary = Color(0xFFE8F1EB),
                textSecondary = Color(0xFF92A89B),
                textTertiary = Color(0xFF5F7568),
                hairline = Color(0x16FFFFFF),
                strongBorder = Color(0x28FFFFFF),
                accent = Color(0xFF34D399),
                accentSoft = Color(0x2210B981),
                onAccent = Color(0xFF052E16),
                sendButtonBg = Color(0xFF10B981),
                sendButtonFg = Color(0xFF042214),
                success = Color(0xFF34D399),
                successSoft = Color(0x2210B981),
                warning = Color(0xFFFBBF24),
                warningSoft = Color(0x22F59E0B),
                danger = Color(0xFFF87171),
                dangerSoft = Color(0x24EF4444)
            )
        }

        OmsThemeStyle.WARM_AMBER -> if (!dark) {
            OmsColorPalette(
                isDark = false,
                canvas = Color(0xFFFBF7F0),
                topBar = Color(0xF2FBF7F0),
                drawer = Color(0xFFF3ECE1),
                composer = Color(0xFFFFFDF9),
                cardSurface = Color(0xFFFFFDF9),
                subtleSurface = Color(0xFFF2EAE0),
                codeSurface = Color(0xFFF4EDE3),
                codeHeaderSurface = Color(0xFFEAE0D2),
                userBubble = Color(0xFFECE0D1),
                userBubbleText = Color(0xFF281C13),
                textPrimary = Color(0xFF231912),
                textSecondary = Color(0xFF6A5A4D),
                textTertiary = Color(0xFF9B8A7C),
                hairline = Color(0x142A1808),
                strongBorder = Color(0x262A1808),
                accent = Color(0xFFB45309),
                accentSoft = Color(0x1AB45309),
                onAccent = Color(0xFFFFFFFF),
                sendButtonBg = Color(0xFF78350F),
                sendButtonFg = Color(0xFFFFFDF9),
                success = Color(0xFF059669),
                successSoft = Color(0x18059669),
                warning = Color(0xFFD97706),
                warningSoft = Color(0x1AD97706),
                danger = Color(0xFFDC2626),
                dangerSoft = Color(0x18DC2626)
            )
        } else {
            OmsColorPalette(
                isDark = true,
                canvas = Color(0xFF100D0B),
                topBar = Color(0xF0100D0B),
                drawer = Color(0xFF171310),
                composer = Color(0xFF221C18),
                cardSurface = Color(0xFF181411),
                subtleSurface = Color(0xFF1C1714),
                codeSurface = Color(0xFF130F0D),
                codeHeaderSurface = Color(0xFF1E1814),
                userBubble = Color(0xFF2F2620),
                userBubbleText = Color(0xFFF5ECE4),
                textPrimary = Color(0xFFF2E9E1),
                textSecondary = Color(0xFFA8998C),
                textTertiary = Color(0xFF736559),
                hairline = Color(0x16FFFFFF),
                strongBorder = Color(0x28FFFFFF),
                accent = Color(0xFFF59E0B),
                accentSoft = Color(0x24F59E0B),
                onAccent = Color(0xFF241302),
                sendButtonBg = Color(0xFFF59E0B),
                sendButtonFg = Color(0xFF1C1004),
                success = Color(0xFF34D399),
                successSoft = Color(0x2210B981),
                warning = Color(0xFFFBBF24),
                warningSoft = Color(0x22F59E0B),
                danger = Color(0xFFF87171),
                dangerSoft = Color(0x24EF4444)
            )
        }
    }
}

@Composable
private fun animatePalette(target: OmsColorPalette): OmsColorPalette {
    val spec = tween<Color>(durationMillis = 240)
    return OmsColorPalette(
        isDark = target.isDark,
        canvas = animateColorAsState(target.canvas, spec, label = "canvas").value,
        topBar = animateColorAsState(target.topBar, spec, label = "topBar").value,
        drawer = animateColorAsState(target.drawer, spec, label = "drawer").value,
        composer = animateColorAsState(target.composer, spec, label = "composer").value,
        cardSurface = animateColorAsState(target.cardSurface, spec, label = "cardSurface").value,
        subtleSurface = animateColorAsState(target.subtleSurface, spec, label = "subtleSurface").value,
        codeSurface = animateColorAsState(target.codeSurface, spec, label = "codeSurface").value,
        codeHeaderSurface = animateColorAsState(target.codeHeaderSurface, spec, label = "codeHeader").value,
        userBubble = animateColorAsState(target.userBubble, spec, label = "userBubble").value,
        userBubbleText = animateColorAsState(target.userBubbleText, spec, label = "userBubbleText").value,
        textPrimary = animateColorAsState(target.textPrimary, spec, label = "textPrimary").value,
        textSecondary = animateColorAsState(target.textSecondary, spec, label = "textSecondary").value,
        textTertiary = animateColorAsState(target.textTertiary, spec, label = "textTertiary").value,
        hairline = animateColorAsState(target.hairline, spec, label = "hairline").value,
        strongBorder = animateColorAsState(target.strongBorder, spec, label = "strongBorder").value,
        accent = animateColorAsState(target.accent, spec, label = "accent").value,
        accentSoft = animateColorAsState(target.accentSoft, spec, label = "accentSoft").value,
        onAccent = animateColorAsState(target.onAccent, spec, label = "onAccent").value,
        sendButtonBg = animateColorAsState(target.sendButtonBg, spec, label = "sendBg").value,
        sendButtonFg = animateColorAsState(target.sendButtonFg, spec, label = "sendFg").value,
        success = animateColorAsState(target.success, spec, label = "success").value,
        successSoft = animateColorAsState(target.successSoft, spec, label = "successSoft").value,
        warning = animateColorAsState(target.warning, spec, label = "warning").value,
        warningSoft = animateColorAsState(target.warningSoft, spec, label = "warningSoft").value,
        danger = animateColorAsState(target.danger, spec, label = "danger").value,
        dangerSoft = animateColorAsState(target.dangerSoft, spec, label = "dangerSoft").value
    )
}

val LocalOmsColors = staticCompositionLocalOf {
    buildRawPalette(OmsThemeStyle.QINGJIAN_INK, dark = true)
}

object OmsTheme {
    val colors: OmsColorPalette
        @Composable get() = LocalOmsColors.current
}

private val OmsEditorialTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 25.5.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun OmsThemeRoot(
    onDarkResolved: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val settings by OmsThemeController.settingsFlow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (settings.mode) {
        OmsAppearanceMode.SYSTEM -> systemDark
        OmsAppearanceMode.LIGHT -> false
        OmsAppearanceMode.DARK -> true
    }
    if (onDarkResolved != null) {
        SideEffect {
            onDarkResolved(effectiveDark)
        }
    }

    val rawPalette = buildRawPalette(settings.style, effectiveDark)
    val palette = animatePalette(rawPalette)

    val materialScheme: ColorScheme = if (effectiveDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.canvas,
            onBackground = palette.textPrimary,
            surface = palette.cardSurface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.subtleSurface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.canvas,
            onBackground = palette.textPrimary,
            surface = palette.cardSurface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.subtleSurface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.hairline
        )
    }

    CompositionLocalProvider(LocalOmsColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = OmsEditorialTypography,
            content = content
        )
    }
}
