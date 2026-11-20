package com.oms.client.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Spring-damped tactile press physics (`0.93f` scale down on press, crisp rebound on release,
 * plus optional subtle haptic feedback).
 */
fun Modifier.omsBounceClick(
    enabled: Boolean = true,
    scaleDown: Float = 0.93f,
    shape: Shape = RoundedCornerShape(14.dp),
    haptic: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleDown else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 680f),
        label = "omsPressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.45f
        }
        .clip(shape)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled
        ) {
            if (haptic) {
                runCatching { hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            }
            onClick()
        }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.omsCombinedBounce(
    enabled: Boolean = true,
    scaleDown: Float = 0.97f,
    shape: Shape = RoundedCornerShape(14.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleDown else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 620f),
        label = "omsCombinedScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onLongClick = if (onLongClick != null) {
                {
                    runCatching { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) }
                    onLongClick()
                }
            } else null,
            onClick = onClick
        )
}

@Composable
fun OmsCircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 19.dp,
    containerColor: Color = Color.Transparent,
    contentColor: Color = OmsTheme.colors.textPrimary,
    borderColor: Color = Color.Transparent,
    badgeColor: Color? = null,
    enabled: Boolean = true
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .omsBounceClick(enabled = enabled, scaleDown = 0.90f, shape = CircleShape, onClick = onClick)
            .background(containerColor, CircleShape)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(0.6.dp, borderColor, CircleShape)
                } else Modifier
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize)
        )
        if (badgeColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(6.5.dp)
                    .background(badgeColor, CircleShape)
            )
        }
    }
}

/**
 * Minimalist icon action button that temporarily morphs into `Check` (or filled icon) upon tap,
 * giving ChatGPT-style tactile confirmation without ugly system Toasts.
 */
@Composable
fun OmsMorphIconAction(
    icon: ImageVector,
    confirmedIcon: ImageVector = OmsIcons.Check,
    contentDescription: String,
    tint: Color = OmsTheme.colors.textTertiary,
    confirmedTint: Color = OmsTheme.colors.success,
    iconSize: Dp = 16.5.dp,
    holdConfirmedMs: Long = 1600L,
    onClick: () -> Unit
) {
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(confirmed) {
        if (confirmed) {
            delay(holdConfirmedMs)
            confirmed = false
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .omsBounceClick(scaleDown = 0.86f, shape = CircleShape) {
                confirmed = true
                onClick()
            }
    ) {
        AnimatedContent(
            targetState = confirmed,
            transitionSpec = {
                (scaleIn(spring(stiffness = 650f)) + fadeIn(tween(130))) togetherWith
                    (scaleOut(tween(110)) + fadeOut(tween(110)))
            },
            label = "morphAction"
        ) { isDone ->
            Icon(
                imageVector = if (isDone) confirmedIcon else icon,
                contentDescription = contentDescription,
                tint = if (isDone) confirmedTint else tint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
fun OmsTogglePill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    activeAccent: Color = OmsTheme.colors.accent
) {
    val palette = OmsTheme.colors
    val bg = if (active) activeAccent.copy(alpha = if (palette.isDark) 0.20f else 0.12f) else Color.Transparent
    val border = if (active) activeAccent.copy(alpha = 0.55f) else palette.hairline
    val fg = if (active) activeAccent else palette.textSecondary
    val shape = RoundedCornerShape(16.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .height(31.dp)
            .omsBounceClick(scaleDown = 0.94f, shape = shape, onClick = onClick)
            .background(bg, shape)
            .border(0.65.dp, border, shape)
            .padding(horizontal = 10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.5.dp)
            )
        }
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

data class OmsSegmentItem<T>(
    val value: T,
    val label: String,
    val dotColor: Color? = null
)

/**
 * Silky spring-animated sliding capsule segmented bar (`对话 · 尺素 · 协同` or `跟随系统 · 浅色 · 深色`).
 */
@Composable
fun <T> OmsSlidingSegmentedBar(
    items: List<OmsSegmentItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp
) {
    val palette = OmsTheme.colors
    val selectedIndex = items.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val outerShape = RoundedCornerShape(height / 2)
    val pillShape = RoundedCornerShape((height - 6.dp) / 2)

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(outerShape)
            .background(palette.subtleSurface)
            .border(0.5.dp, palette.hairline, outerShape)
            .padding(3.dp)
    ) {
        val count = items.size.coerceAtLeast(1)
        val itemWidth = maxWidth / count
        val animatedOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "segSlideOffset"
        )

        // Sliding Pill Indicator
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .shadow(
                    elevation = if (palette.isDark) 0.dp else 1.5.dp,
                    shape = pillShape,
                    clip = false
                )
                .clip(pillShape)
                .background(palette.composer)
                .border(0.5.dp, palette.hairline, pillShape)
        )

        // Interactive Labels
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEach { seg ->
                val isSelected = seg.value == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .omsBounceClick(scaleDown = 0.95f, shape = pillShape) {
                            onSelect(seg.value)
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = seg.label,
                            color = if (isSelected) palette.textPrimary else palette.textTertiary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (seg.dotColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(5.5.dp)
                                    .background(seg.dotColor, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OmsSurfaceCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    containerColor: Color = OmsTheme.colors.cardSurface,
    borderColor: Color = OmsTheme.colors.hairline,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val clickMod = if (onClick != null) {
        Modifier.omsBounceClick(scaleDown = 0.985f, shape = shape, onClick = onClick)
    } else Modifier

    Column(
        modifier = modifier
            .then(clickMod)
            .clip(shape)
            .background(containerColor, shape)
            .border(0.6.dp, borderColor, shape)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun OmsPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = OmsTheme.colors.sendButtonBg,
    contentColor: Color = OmsTheme.colors.sendButtonFg,
    height: Dp = 40.dp
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(height)
            .omsBounceClick(enabled = enabled, scaleDown = 0.95f, shape = shape, onClick = onClick)
            .background(containerColor, shape)
            .padding(horizontal = 16.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun OmsSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = OmsTheme.colors.textPrimary,
    height: Dp = 38.dp
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(height / 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(height)
            .omsBounceClick(enabled = enabled, scaleDown = 0.95f, shape = shape, onClick = onClick)
            .background(palette.subtleSurface, shape)
            .border(0.6.dp, palette.hairline, shape)
            .padding(horizontal = 14.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Non-intrusive floating Dynamic Pill Toast at top of screen, replacing legacy Android system Toasts.
 */
@Composable
fun OmsFloatingToastHost(modifier: Modifier = Modifier) {
    val toast by OmsFeedbackController.toastFlow.collectAsState()
    val palette = OmsTheme.colors

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 56.dp, start = 20.dp, end = 20.dp)
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically(
                initialOffsetY = { -it - 24 },
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 500f)
            ) + fadeIn(tween(150)),
            exit = slideOutVertically(
                targetOffsetY = { -it - 24 },
                animationSpec = tween(160)
            ) + fadeOut(tween(140))
        ) {
            val current = toast
            if (current != null) {
                val (icon, iconTint) = when (current.kind) {
                    OmsToastKind.SUCCESS -> OmsIcons.Check to palette.success
                    OmsToastKind.WARNING -> OmsIcons.Shield to palette.warning
                    OmsToastKind.INFO -> OmsIcons.Sparkles to palette.accent
                }
                val shape = RoundedCornerShape(22.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .shadow(elevation = 8.dp, shape = shape, clip = false)
                        .clip(shape)
                        .background(palette.composer)
                        .border(0.7.dp, palette.strongBorder, shape)
                        .clickable { OmsFeedbackController.dismiss() }
                        .padding(horizontal = 15.dp, vertical = 9.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = current.text,
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
