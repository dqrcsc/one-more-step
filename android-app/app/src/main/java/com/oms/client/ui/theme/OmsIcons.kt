package com.oms.client.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-crafted 24x24 rounded linear vector iconography (`1.85dp` stroke, round caps & joins),
 * unifying visual weight across Chat, Reader, Hub, Live Voice, Camera, and Settings.
 */
object OmsIcons {

    private fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.85f,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    private fun ImageVector.Builder.linePath(
        strokeWidth: Float = 1.85f,
        fillAlpha: Float = 0f,
        builder: PathBuilder.() -> Unit
    ) {
        path(
            fill = if (fillAlpha > 0f) SolidColor(Color.Black.copy(alpha = fillAlpha)) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = builder
        )
    }

    private fun ImageVector.Builder.solidPath(builder: PathBuilder.() -> Unit) {
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            pathBuilder = builder
        )
    }

    // 1. Navigation & Shell
    val Menu: ImageVector by lazy {
        strokeIcon("Menu") {
            linePath {
                moveTo(4f, 7f); horizontalLineTo(20f)
                moveTo(4f, 12f); horizontalLineTo(14.5f)
                moveTo(4f, 17f); horizontalLineTo(20f)
            }
        }
    }

    val NewChat: ImageVector by lazy {
        strokeIcon("NewChat") {
            linePath {
                moveTo(12f, 4f)
                horizontalLineTo(6.5f)
                curveTo(5.12f, 4f, 4f, 5.12f, 4f, 6.5f)
                verticalLineTo(17.5f)
                curveTo(4f, 18.88f, 5.12f, 20f, 6.5f, 20f)
                horizontalLineTo(17.5f)
                curveTo(18.88f, 20f, 20f, 18.88f, 20f, 17.5f)
                verticalLineTo(12f)
            }
            linePath {
                moveTo(18.4f, 3.6f)
                curveTo(19.2f, 2.8f, 20.5f, 2.8f, 21.3f, 3.6f)
                curveTo(22.1f, 4.4f, 22.1f, 5.7f, 21.3f, 6.5f)
                lineTo(12.5f, 15.3f)
                lineTo(8.8f, 16.2f)
                lineTo(9.7f, 12.5f)
                close()
            }
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") {
            linePath {
                moveTo(18f, 11f)
                curveTo(18f, 14.87f, 14.87f, 18f, 11f, 18f)
                curveTo(7.13f, 18f, 4f, 14.87f, 4f, 11f)
                curveTo(4f, 7.13f, 7.13f, 4f, 11f, 4f)
                curveTo(14.87f, 4f, 18f, 7.13f, 18f, 11f)
                close()
                moveTo(20f, 20f)
                lineTo(16.2f, 16.2f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus", strokeWidth = 2.0f) {
            linePath(strokeWidth = 2.0f) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(5f, 12f); horizontalLineTo(19f)
            }
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            linePath {
                moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
            }
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check", strokeWidth = 2.1f) {
            linePath(strokeWidth = 2.1f) {
                moveTo(4.5f, 12.5f)
                lineTo(9.5f, 17.5f)
                lineTo(19.5f, 7f)
            }
        }
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp", strokeWidth = 2.2f) {
            linePath(strokeWidth = 2.2f) {
                moveTo(12f, 19f); verticalLineTo(5f)
                moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
            }
        }
    }

    val ArrowDown: ImageVector by lazy {
        strokeIcon("ArrowDown", strokeWidth = 2.0f) {
            linePath(strokeWidth = 2.0f) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f)
            }
        }
    }

    val ArrowLeft: ImageVector by lazy {
        strokeIcon("ArrowLeft") {
            linePath {
                moveTo(19f, 12f); horizontalLineTo(5f)
                moveTo(11.5f, 5.5f); lineTo(5f, 12f); lineTo(11.5f, 18.5f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            linePath {
                moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown") {
            linePath {
                moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
            }
        }
    }

    val ChevronUp: ImageVector by lazy {
        strokeIcon("ChevronUp") {
            linePath {
                moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f)
            }
        }
    }

    val MoreHorizontal: ImageVector by lazy {
        strokeIcon("MoreHorizontal") {
            solidPath {
                moveTo(6.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 3.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 6.5f, 12f)
                close()
                moveTo(13.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 10.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, 12f)
                close()
                moveTo(20.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 17.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 20.5f, 12f)
                close()
            }
        }
    }

    // 2. AI, Thinking & Modes
    val Sparkles: ImageVector by lazy {
        strokeIcon("Sparkles") {
            linePath {
                moveTo(11.5f, 3f)
                curveTo(12.1f, 7.2f, 14.8f, 9.9f, 19f, 10.5f)
                curveTo(14.8f, 11.1f, 12.1f, 13.8f, 11.5f, 18f)
                curveTo(10.9f, 13.8f, 8.2f, 11.1f, 4f, 10.5f)
                curveTo(8.2f, 9.9f, 10.9f, 7.2f, 11.5f, 3f)
                close()
                moveTo(18.5f, 16f); verticalLineTo(20f)
                moveTo(16.5f, 18f); horizontalLineTo(20.5f)
            }
        }
    }

    val Lightbulb: ImageVector by lazy {
        strokeIcon("Lightbulb") {
            linePath {
                moveTo(9f, 18f); horizontalLineTo(15f)
                moveTo(10f, 21f); horizontalLineTo(14f)
                moveTo(15f, 14.5f)
                curveTo(16.3f, 13.4f, 17.2f, 11.7f, 17.2f, 9.8f)
                curveTo(17.2f, 6.6f, 14.9f, 4f, 12f, 4f)
                curveTo(9.1f, 4f, 6.8f, 6.6f, 6.8f, 9.8f)
                curveTo(6.8f, 11.7f, 7.7f, 13.4f, 9f, 14.5f)
                lineTo(9.5f, 15.5f)
                horizontalLineTo(14.5f)
                close()
            }
        }
    }

    val Globe: ImageVector by lazy {
        strokeIcon("Globe") {
            linePath {
                moveTo(21f, 12f)
                curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
                curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
                curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
                curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
                close()
                moveTo(3.5f, 12f); horizontalLineTo(20.5f)
                moveTo(12f, 3f)
                curveTo(14.6f, 5.5f, 15.8f, 8.7f, 15.8f, 12f)
                curveTo(15.8f, 15.3f, 14.6f, 18.5f, 12f, 21f)
                curveTo(9.4f, 18.5f, 8.2f, 15.3f, 8.2f, 12f)
                curveTo(8.2f, 8.7f, 9.4f, 5.5f, 12f, 3f)
                close()
            }
        }
    }

    val Incognito: ImageVector by lazy {
        strokeIcon("Incognito") {
            linePath {
                moveTo(3f, 11f); horizontalLineTo(21f)
                moveTo(5.5f, 11f); lineTo(7.5f, 5.5f); horizontalLineTo(16.5f); lineTo(18.5f, 11f)
                moveTo(10f, 15.5f)
                curveTo(10f, 17.16f, 8.66f, 18.5f, 7f, 18.5f)
                curveTo(5.34f, 18.5f, 4f, 17.16f, 4f, 15.5f)
                curveTo(4f, 13.84f, 5.34f, 12.5f, 7f, 12.5f)
                curveTo(8.66f, 12.5f, 10f, 13.84f, 10f, 15.5f)
                close()
                moveTo(20f, 15.5f)
                curveTo(20f, 17.16f, 18.66f, 18.5f, 17f, 18.5f)
                curveTo(15.34f, 18.5f, 14f, 17.16f, 14f, 15.5f)
                curveTo(14f, 13.84f, 15.34f, 12.5f, 17f, 12.5f)
                curveTo(18.66f, 12.5f, 20f, 13.84f, 20f, 15.5f)
                close()
                moveTo(10f, 15.2f); curveTo(11f, 14.3f, 13f, 14.3f, 14f, 15.2f)
            }
        }
    }

    // 3. Voice, Audio & Media
    val Mic: ImageVector by lazy {
        strokeIcon("Mic") {
            linePath {
                moveTo(12f, 3.5f)
                curveTo(13.66f, 3.5f, 15f, 4.84f, 15f, 6.5f)
                verticalLineTo(11.5f)
                curveTo(15f, 13.16f, 13.66f, 14.5f, 12f, 14.5f)
                curveTo(10.34f, 14.5f, 9f, 13.16f, 9f, 11.5f)
                verticalLineTo(6.5f)
                curveTo(9f, 4.84f, 10.34f, 3.5f, 12f, 3.5f)
                close()
                moveTo(18.5f, 11f)
                curveTo(18.5f, 14.6f, 15.6f, 17.5f, 12f, 17.5f)
                curveTo(8.4f, 17.5f, 5.5f, 14.6f, 5.5f, 11f)
                moveTo(12f, 17.5f); verticalLineTo(21f)
            }
        }
    }

    val MicOff: ImageVector by lazy {
        strokeIcon("MicOff") {
            linePath {
                moveTo(4f, 4f); lineTo(20f, 20f)
                moveTo(9f, 9f); verticalLineTo(11.5f)
                curveTo(9f, 13.16f, 10.34f, 14.5f, 12f, 14.5f)
                curveTo(12.8f, 14.5f, 13.5f, 14.2f, 14f, 13.7f)
                moveTo(15f, 9.5f); verticalLineTo(6.5f)
                curveTo(15f, 4.84f, 13.66f, 3.5f, 12f, 3.5f)
                curveTo(11.1f, 3.5f, 10.3f, 3.9f, 9.8f, 4.5f)
                moveTo(18.5f, 11f)
                curveTo(18.5f, 12.4f, 18f, 13.7f, 17.2f, 14.8f)
                moveTo(14.8f, 16.9f)
                curveTo(13.9f, 17.3f, 13f, 17.5f, 12f, 17.5f)
                curveTo(8.4f, 17.5f, 5.5f, 14.6f, 5.5f, 11f)
                moveTo(12f, 17.5f); verticalLineTo(21f)
            }
        }
    }

    val Waveform: ImageVector by lazy {
        strokeIcon("Waveform", strokeWidth = 2.0f) {
            linePath(strokeWidth = 2.0f) {
                moveTo(4.5f, 10f); verticalLineTo(14f)
                moveTo(8.2f, 6.5f); verticalLineTo(17.5f)
                moveTo(12f, 3.5f); verticalLineTo(20.5f)
                moveTo(15.8f, 7.5f); verticalLineTo(16.5f)
                moveTo(19.5f, 10f); verticalLineTo(14f)
            }
        }
    }

    val StopSquare: ImageVector by lazy {
        strokeIcon("StopSquare") {
            solidPath {
                moveTo(8.5f, 7f)
                horizontalLineTo(15.5f)
                curveTo(16.33f, 7f, 17f, 7.67f, 17f, 8.5f)
                verticalLineTo(15.5f)
                curveTo(17f, 16.33f, 16.33f, 17f, 15.5f, 17f)
                horizontalLineTo(8.5f)
                curveTo(7.67f, 17f, 7f, 16.33f, 7f, 15.5f)
                verticalLineTo(8.5f)
                curveTo(7f, 7.67f, 7.67f, 7f, 8.5f, 7f)
                close()
            }
        }
    }

    val Play: ImageVector by lazy {
        strokeIcon("Play") {
            solidPath {
                moveTo(7.5f, 5.4f)
                lineTo(18.5f, 12f)
                lineTo(7.5f, 18.6f)
                close()
            }
        }
    }

    val Volume: ImageVector by lazy {
        strokeIcon("Volume") {
            linePath {
                moveTo(11f, 5.5f)
                lineTo(6.5f, 9f)
                horizontalLineTo(3.5f)
                verticalLineTo(15f)
                horizontalLineTo(6.5f)
                lineTo(11f, 18.5f)
                close()
                moveTo(15f, 9f)
                curveTo(16.2f, 10.2f, 16.2f, 13.8f, 15f, 15f)
                moveTo(17.8f, 6.5f)
                curveTo(20.3f, 9.3f, 20.3f, 14.7f, 17.8f, 17.5f)
            }
        }
    }

    // 4. Multimodal, Camera, Leaf & Files
    val Camera: ImageVector by lazy {
        strokeIcon("Camera") {
            linePath {
                moveTo(4f, 8.5f)
                curveTo(4f, 7.4f, 4.9f, 6.5f, 6f, 6.5f)
                horizontalLineTo(7.8f)
                lineTo(9.3f, 4.5f)
                horizontalLineTo(14.7f)
                lineTo(16.2f, 6.5f)
                horizontalLineTo(18f)
                curveTo(19.1f, 6.5f, 20f, 7.4f, 20f, 8.5f)
                verticalLineTo(17.5f)
                curveTo(20f, 18.6f, 19.1f, 19.5f, 18f, 19.5f)
                horizontalLineTo(6f)
                curveTo(4.9f, 19.5f, 4f, 18.6f, 4f, 17.5f)
                close()
                moveTo(15.5f, 13f)
                curveTo(15.5f, 14.93f, 13.93f, 16.5f, 12f, 16.5f)
                curveTo(10.07f, 16.5f, 8.5f, 14.93f, 8.5f, 13f)
                curveTo(8.5f, 11.07f, 10.07f, 9.5f, 12f, 9.5f)
                curveTo(13.93f, 9.5f, 15.5f, 11.07f, 15.5f, 13f)
                close()
            }
        }
    }

    val Leaf: ImageVector by lazy {
        strokeIcon("Leaf") {
            linePath {
                moveTo(5f, 19f)
                curveTo(5f, 11f, 10f, 5f, 19f, 4.5f)
                curveTo(19.5f, 13.5f, 14.5f, 19f, 6.5f, 19f)
                close()
                moveTo(4.5f, 19.5f)
                curveTo(8.5f, 15.5f, 12f, 12f, 15f, 9.5f)
            }
        }
    }

    val Image: ImageVector by lazy {
        strokeIcon("Image") {
            linePath {
                moveTo(5.5f, 4.5f)
                horizontalLineTo(18.5f)
                curveTo(19.6f, 4.5f, 20.5f, 5.4f, 20.5f, 6.5f)
                verticalLineTo(17.5f)
                curveTo(20.5f, 18.6f, 19.6f, 19.5f, 18.5f, 19.5f)
                horizontalLineTo(5.5f)
                curveTo(4.4f, 19.5f, 3.5f, 18.6f, 3.5f, 17.5f)
                verticalLineTo(6.5f)
                curveTo(3.5f, 5.4f, 4.4f, 4.5f, 5.5f, 4.5f)
                close()
                moveTo(10f, 9f)
                curveTo(10f, 9.83f, 9.33f, 10.5f, 8.5f, 10.5f)
                curveTo(7.67f, 10.5f, 7f, 9.83f, 7f, 9f)
                curveTo(7f, 8.17f, 7.67f, 7.5f, 8.5f, 7.5f)
                curveTo(9.33f, 7.5f, 10f, 8.17f, 10f, 9f)
                close()
                moveTo(20.2f, 15.5f)
                lineTo(15.2f, 11f)
                lineTo(6.5f, 19.2f)
            }
        }
    }

    val Paperclip: ImageVector by lazy {
        strokeIcon("Paperclip") {
            linePath {
                moveTo(18.4f, 11.2f)
                lineTo(10.8f, 18.8f)
                curveTo(8.8f, 20.8f, 5.6f, 20.8f, 3.6f, 18.8f)
                curveTo(1.6f, 16.8f, 1.6f, 13.6f, 3.6f, 11.6f)
                lineTo(11.8f, 3.4f)
                curveTo(13.1f, 2.1f, 15.3f, 2.1f, 16.6f, 3.4f)
                curveTo(17.9f, 4.7f, 17.9f, 6.9f, 16.6f, 8.2f)
                lineTo(8.5f, 16.3f)
                curveTo(7.8f, 17f, 6.7f, 17f, 6f, 16.3f)
                curveTo(5.3f, 15.6f, 5.3f, 14.5f, 6f, 13.8f)
                lineTo(13.5f, 6.3f)
            }
        }
    }

    val FileText: ImageVector by lazy {
        strokeIcon("FileText") {
            linePath {
                moveTo(14f, 3.5f)
                horizontalLineTo(7f)
                curveTo(5.9f, 3.5f, 5f, 4.4f, 5f, 5.5f)
                verticalLineTo(18.5f)
                curveTo(5f, 19.6f, 5.9f, 20.5f, 7f, 20.5f)
                horizontalLineTo(17f)
                curveTo(18.1f, 20.5f, 19f, 19.6f, 19f, 18.5f)
                verticalLineTo(8.5f)
                close()
                moveTo(14f, 3.5f); verticalLineTo(8.5f); horizontalLineTo(19f)
                moveTo(8.5f, 13f); horizontalLineTo(15.5f)
                moveTo(8.5f, 16.5f); horizontalLineTo(13.5f)
            }
        }
    }

    val Palette: ImageVector by lazy {
        strokeIcon("Palette") {
            linePath {
                moveTo(12f, 3f)
                curveTo(7.03f, 3f, 3f, 6.8f, 3f, 11.5f)
                curveTo(3f, 16.2f, 6.8f, 20f, 11.5f, 20f)
                horizontalLineTo(13f)
                curveTo(14.1f, 20f, 15f, 19.1f, 15f, 18f)
                curveTo(15f, 17.4f, 14.7f, 16.9f, 14.4f, 16.5f)
                curveTo(14.1f, 16.1f, 13.9f, 15.6f, 14.2f, 15f)
                curveTo(14.5f, 14.4f, 15.1f, 14f, 15.8f, 14f)
                horizontalLineTo(17.5f)
                curveTo(19.4f, 14f, 21f, 12.4f, 21f, 10.5f)
                curveTo(21f, 6.4f, 17f, 3f, 12f, 3f)
                close()
            }
            solidPath {
                moveTo(8.5f, 10.5f); arcTo(1.2f, 1.2f, 0f, true, true, 6.1f, 10.5f); arcTo(1.2f, 1.2f, 0f, true, true, 8.5f, 10.5f); close()
                moveTo(11.7f, 7.5f); arcTo(1.2f, 1.2f, 0f, true, true, 9.3f, 7.5f); arcTo(1.2f, 1.2f, 0f, true, true, 11.7f, 7.5f); close()
                moveTo(16.2f, 8.5f); arcTo(1.2f, 1.2f, 0f, true, true, 13.8f, 8.5f); arcTo(1.2f, 1.2f, 0f, true, true, 16.2f, 8.5f); close()
            }
        }
    }

    val Video: ImageVector by lazy {
        strokeIcon("Video") {
            linePath {
                moveTo(4.5f, 6.5f)
                horizontalLineTo(13.5f)
                curveTo(14.6f, 6.5f, 15.5f, 7.4f, 15.5f, 8.5f)
                verticalLineTo(15.5f)
                curveTo(15.5f, 16.6f, 14.6f, 17.5f, 13.5f, 17.5f)
                horizontalLineTo(4.5f)
                curveTo(3.4f, 17.5f, 2.5f, 16.6f, 2.5f, 15.5f)
                verticalLineTo(8.5f)
                curveTo(2.5f, 7.4f, 3.4f, 6.5f, 4.5f, 6.5f)
                close()
                moveTo(15.5f, 10.5f); lineTo(21f, 7.5f); verticalLineTo(16.5f); lineTo(15.5f, 13.5f)
            }
        }
    }

    val Music: ImageVector by lazy {
        strokeIcon("Music") {
            linePath {
                moveTo(9f, 17.5f); verticalLineTo(6f); lineTo(19.5f, 4f); verticalLineTo(15.5f)
                moveTo(9f, 17.5f)
                curveTo(9f, 18.88f, 7.88f, 20f, 6.5f, 20f)
                curveTo(5.12f, 20f, 4f, 18.88f, 4f, 17.5f)
                curveTo(4f, 16.12f, 5.12f, 15f, 6.5f, 15f)
                curveTo(7.88f, 15f, 9f, 16.12f, 9f, 17.5f)
                close()
                moveTo(19.5f, 15.5f)
                curveTo(19.5f, 16.88f, 18.38f, 18f, 17f, 18f)
                curveTo(15.62f, 18f, 14.5f, 16.88f, 14.5f, 15.5f)
                curveTo(14.5f, 14.12f, 15.62f, 13f, 17f, 13f)
                curveTo(18.38f, 13f, 19.5f, 14.12f, 19.5f, 15.5f)
                close()
            }
        }
    }

    // 5. Actions, Collaboration & System
    val Copy: ImageVector by lazy {
        strokeIcon("Copy") {
            linePath {
                moveTo(9.5f, 9.5f)
                horizontalLineTo(18f)
                curveTo(19.1f, 9.5f, 20f, 10.4f, 20f, 11.5f)
                verticalLineTo(18f)
                curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f)
                horizontalLineTo(9.5f)
                curveTo(8.4f, 20f, 7.5f, 19.1f, 7.5f, 18f)
                verticalLineTo(11.5f)
                curveTo(7.5f, 10.4f, 8.4f, 9.5f, 9.5f, 9.5f)
                close()
                moveTo(5.5f, 15f)
                horizontalLineTo(5f)
                curveTo(4.17f, 15f, 3.5f, 14.33f, 3.5f, 13.5f)
                verticalLineTo(5.5f)
                curveTo(3.5f, 4.4f, 4.4f, 3.5f, 5.5f, 3.5f)
                horizontalLineTo(13.5f)
                curveTo(14.33f, 3.5f, 15f, 4.17f, 15f, 5f)
                verticalLineTo(5.5f)
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        strokeIcon("Bookmark") {
            linePath {
                moveTo(6.5f, 4f)
                horizontalLineTo(17.5f)
                curveTo(18.33f, 4f, 19f, 4.67f, 19f, 5.5f)
                verticalLineTo(20f)
                lineTo(12f, 15.8f)
                lineTo(5f, 20f)
                verticalLineTo(5.5f)
                curveTo(5f, 4.67f, 5.67f, 4f, 6.5f, 4f)
                close()
            }
        }
    }

    val BookmarkFilled: ImageVector by lazy {
        strokeIcon("BookmarkFilled") {
            solidPath {
                moveTo(6.5f, 4f)
                horizontalLineTo(17.5f)
                curveTo(18.33f, 4f, 19f, 4.67f, 19f, 5.5f)
                verticalLineTo(20f)
                lineTo(12f, 15.8f)
                lineTo(5f, 20f)
                verticalLineTo(5.5f)
                curveTo(5f, 4.67f, 5.67f, 4f, 6.5f, 4f)
                close()
            }
        }
    }

    val Laptop: ImageVector by lazy {
        strokeIcon("Laptop") {
            linePath {
                moveTo(4.5f, 5.5f)
                horizontalLineTo(19.5f)
                curveTo(20.33f, 5.5f, 21f, 6.17f, 21f, 7f)
                verticalLineTo(15.5f)
                horizontalLineTo(3f)
                verticalLineTo(7f)
                curveTo(3f, 6.17f, 3.67f, 5.5f, 4.5f, 5.5f)
                close()
                moveTo(2f, 18.5f); horizontalLineTo(22f)
            }
        }
    }

    val Terminal: ImageVector by lazy {
        strokeIcon("Terminal") {
            linePath {
                moveTo(4f, 17f); lineTo(10f, 11f); lineTo(4f, 5f)
                moveTo(12f, 19f); horizontalLineTo(20f)
            }
        }
    }

    val Send: ImageVector by lazy {
        strokeIcon("Send") {
            linePath {
                moveTo(21f, 3f)
                lineTo(10f, 14f)
                moveTo(21f, 3f)
                lineTo(14.5f, 21f)
                lineTo(10f, 14f)
                lineTo(3f, 9.5f)
                close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            linePath {
                moveTo(20f, 11f)
                curveTo(19.5f, 6.8f, 16f, 3.8f, 11.8f, 4f)
                curveTo(7.5f, 4.2f, 4f, 7.7f, 4f, 12f)
                curveTo(4f, 16.4f, 7.6f, 20f, 12f, 20f)
                curveTo(15.4f, 20f, 18.3f, 17.9f, 19.4f, 14.9f)
                moveTo(20f, 5.5f); verticalLineTo(11f); horizontalLineTo(14.5f)
            }
        }
    }

    val Sliders: ImageVector by lazy {
        strokeIcon("Sliders") {
            linePath {
                moveTo(4f, 7f); horizontalLineTo(10f)
                moveTo(14f, 7f); horizontalLineTo(20f)
                moveTo(14f, 7f)
                arcTo(2f, 2f, 0f, true, true, 10f, 7f)
                arcTo(2f, 2f, 0f, true, true, 14f, 7f)
                close()

                moveTo(4f, 12f); horizontalLineTo(14f)
                moveTo(18f, 12f); horizontalLineTo(20f)
                moveTo(18f, 12f)
                arcTo(2f, 2f, 0f, true, true, 14f, 12f)
                arcTo(2f, 2f, 0f, true, true, 18f, 12f)
                close()

                moveTo(4f, 17f); horizontalLineTo(7f)
                moveTo(11f, 17f); horizontalLineTo(20f)
                moveTo(11f, 17f)
                arcTo(2f, 2f, 0f, true, true, 7f, 17f)
                arcTo(2f, 2f, 0f, true, true, 11f, 17f)
                close()
            }
        }
    }

    val Pin: ImageVector by lazy {
        strokeIcon("Pin") {
            linePath {
                moveTo(15f, 4.5f); lineTo(19.5f, 9f)
                lineTo(15.5f, 13f); lineTo(14.5f, 16.5f)
                lineTo(7.5f, 9.5f); lineTo(11f, 8.5f)
                close()
                moveTo(11f, 13f); lineTo(5f, 19f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            linePath {
                moveTo(4.5f, 6.5f); horizontalLineTo(19.5f)
                moveTo(9.5f, 6.5f); verticalLineTo(4.5f); horizontalLineTo(14.5f); verticalLineTo(6.5f)
                moveTo(6.5f, 6.5f); lineTo(7.3f, 19f)
                curveTo(7.35f, 19.8f, 8f, 20.5f, 8.8f, 20.5f)
                horizontalLineTo(15.2f)
                curveTo(16f, 20.5f, 16.65f, 19.8f, 16.7f, 19f)
                lineTo(17.5f, 6.5f)
            }
        }
    }

    val Shield: ImageVector by lazy {
        strokeIcon("Shield") {
            linePath {
                moveTo(12f, 3f)
                lineTo(19f, 6f)
                verticalLineTo(11.5f)
                curveTo(19f, 16.1f, 16f, 19.7f, 12f, 21f)
                curveTo(8f, 19.7f, 5f, 16.1f, 5f, 11.5f)
                verticalLineTo(6f)
                close()
                moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15.2f, 9.8f)
            }
        }
    }

    val Bolt: ImageVector by lazy {
        strokeIcon("Bolt") {
            linePath {
                moveTo(13f, 3f)
                lineTo(5.5f, 13.5f)
                horizontalLineTo(12f)
                lineTo(11f, 21f)
                lineTo(18.5f, 10.5f)
                horizontalLineTo(12f)
                close()
            }
        }
    }

    val Calendar: ImageVector by lazy {
        strokeIcon("Calendar") {
            linePath {
                moveTo(5.5f, 5f)
                horizontalLineTo(18.5f)
                curveTo(19.6f, 5f, 20.5f, 5.9f, 20.5f, 7f)
                verticalLineTo(18.5f)
                curveTo(20.5f, 19.6f, 19.6f, 20.5f, 18.5f, 20.5f)
                horizontalLineTo(5.5f)
                curveTo(4.4f, 20.5f, 3.5f, 19.6f, 3.5f, 18.5f)
                verticalLineTo(7f)
                curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
                close()
                moveTo(8f, 3.2f); verticalLineTo(6.5f)
                moveTo(16f, 3.2f); verticalLineTo(6.5f)
                moveTo(3.5f, 9.5f); horizontalLineTo(20.5f)
            }
        }
    }

    val Clock: ImageVector by lazy {
        strokeIcon("Clock") {
            linePath {
                moveTo(20.5f, 12f)
                curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
                curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f)
                curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f)
                curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
                close()
                moveTo(12f, 7.5f); verticalLineTo(12f); lineTo(15.2f, 14.2f)
            }
        }
    }

    val Sun: ImageVector by lazy {
        strokeIcon("Sun") {
            linePath {
                moveTo(16f, 12f)
                curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f)
                curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f)
                curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f)
                curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f)
                close()
                moveTo(12f, 3f); verticalLineTo(5f)
                moveTo(12f, 19f); verticalLineTo(21f)
                moveTo(3f, 12f); horizontalLineTo(5f)
                moveTo(19f, 12f); horizontalLineTo(21f)
                moveTo(5.6f, 5.6f); lineTo(7f, 7f)
                moveTo(17f, 17f); lineTo(18.4f, 18.4f)
                moveTo(18.4f, 5.6f); lineTo(17f, 7f)
                moveTo(7f, 17f); lineTo(5.6f, 18.4f)
            }
        }
    }

    val Moon: ImageVector by lazy {
        strokeIcon("Moon") {
            linePath {
                moveTo(19.5f, 14.5f)
                curveTo(18.3f, 15f, 17f, 15.2f, 15.6f, 15.2f)
                curveTo(10.8f, 15.2f, 6.8f, 11.2f, 6.8f, 6.4f)
                curveTo(6.8f, 5f, 7f, 3.7f, 7.5f, 2.5f)
                curveTo(4.8f, 3.8f, 3f, 6.7f, 3f, 10f)
                curveTo(3f, 14.97f, 7.03f, 19f, 12f, 19f)
                curveTo(15.3f, 19f, 18.2f, 17.2f, 19.5f, 14.5f)
                close()
            }
        }
    }

    val BookOpen: ImageVector by lazy {
        strokeIcon("BookOpen") {
            linePath {
                moveTo(12f, 6.5f)
                curveTo(10.2f, 5.2f, 7.8f, 4.8f, 4f, 5f)
                verticalLineTo(18.5f)
                curveTo(7.8f, 18.3f, 10.2f, 18.7f, 12f, 20f)
                curveTo(13.8f, 18.7f, 16.2f, 18.3f, 20f, 18.5f)
                verticalLineTo(5f)
                curveTo(16.2f, 4.8f, 13.8f, 5.2f, 12f, 6.5f)
                close()
                moveTo(12f, 6.5f); verticalLineTo(19.8f)
            }
        }
    }

    val Flashlight: ImageVector by lazy {
        strokeIcon("Flashlight") {
            linePath {
                moveTo(7f, 3.5f); horizontalLineTo(17f); verticalLineTo(7f)
                lineTo(14.5f, 10.5f); verticalLineTo(20.5f); horizontalLineTo(9.5f)
                verticalLineTo(10.5f); lineTo(7f, 7f); close()
                moveTo(7f, 7f); horizontalLineTo(17f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        strokeIcon("Folder") {
            linePath {
                moveTo(3.5f, 6.5f)
                curveTo(3.5f, 5.4f, 4.4f, 4.5f, 5.5f, 4.5f)
                horizontalLineTo(9.4f)
                lineTo(11.4f, 6.8f)
                horizontalLineTo(18.5f)
                curveTo(19.6f, 6.8f, 20.5f, 7.7f, 20.5f, 8.8f)
                verticalLineTo(17.5f)
                curveTo(20.5f, 18.6f, 19.6f, 19.5f, 18.5f, 19.5f)
                horizontalLineTo(5.5f)
                curveTo(4.4f, 19.5f, 3.5f, 18.6f, 3.5f, 17.5f)
                close()
            }
        }
    }

    val Cpu: ImageVector by lazy {
        strokeIcon("Cpu") {
            linePath {
                moveTo(7f, 5.5f); horizontalLineTo(17f)
                curveTo(18f, 5.5f, 18.5f, 6f, 18.5f, 7f)
                verticalLineTo(17f)
                curveTo(18.5f, 18f, 18f, 18.5f, 17f, 18.5f)
                horizontalLineTo(7f)
                curveTo(6f, 18.5f, 5.5f, 18f, 5.5f, 17f)
                verticalLineTo(7f)
                curveTo(5.5f, 6f, 6f, 5.5f, 7f, 5.5f)
                close()
                moveTo(9.5f, 9.5f); horizontalLineTo(14.5f); verticalLineTo(14.5f); horizontalLineTo(9.5f); close()
                moveTo(9f, 3f); verticalLineTo(5.5f)
                moveTo(15f, 3f); verticalLineTo(5.5f)
                moveTo(9f, 18.5f); verticalLineTo(21f)
                moveTo(15f, 18.5f); verticalLineTo(21f)
                moveTo(3f, 9f); horizontalLineTo(5.5f)
                moveTo(3f, 15f); horizontalLineTo(5.5f)
                moveTo(18.5f, 9f); horizontalLineTo(21f)
                moveTo(18.5f, 15f); horizontalLineTo(21f)
            }
        }
    }

    val LayoutGrid: ImageVector by lazy {
        strokeIcon("LayoutGrid") {
            linePath {
                moveTo(4.5f, 4.5f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4.5f); close()
                moveTo(14f, 4.5f); horizontalLineTo(19.5f); verticalLineTo(10f); horizontalLineTo(14f); close()
                moveTo(4.5f, 14f); horizontalLineTo(10f); verticalLineTo(19.5f); horizontalLineTo(4.5f); close()
                moveTo(14f, 14f); horizontalLineTo(19.5f); verticalLineTo(19.5f); horizontalLineTo(14f); close()
            }
        }
    }

    val Smartphone: ImageVector by lazy {
        strokeIcon("Smartphone") {
            linePath {
                moveTo(7.5f, 3.5f)
                horizontalLineTo(16.5f)
                curveTo(17.6f, 3.5f, 18.5f, 4.4f, 18.5f, 5.5f)
                verticalLineTo(18.5f)
                curveTo(18.5f, 19.6f, 17.6f, 20.5f, 16.5f, 20.5f)
                horizontalLineTo(7.5f)
                curveTo(6.4f, 20.5f, 5.5f, 19.6f, 5.5f, 18.5f)
                verticalLineTo(5.5f)
                curveTo(5.5f, 4.4f, 6.4f, 3.5f, 7.5f, 3.5f)
                close()
                moveTo(11f, 17.2f); horizontalLineTo(13f)
            }
        }
    }

    val MessageCircle: ImageVector by lazy {
        strokeIcon("MessageCircle") {
            linePath {
                moveTo(20f, 11.5f)
                curveTo(20f, 15.64f, 16.42f, 19f, 12f, 19f)
                curveTo(10.6f, 19f, 9.28f, 18.66f, 8.13f, 18.06f)
                lineTo(4f, 19.5f)
                lineTo(5.32f, 15.96f)
                curveTo(4.48f, 14.68f, 4f, 13.15f, 4f, 11.5f)
                curveTo(4f, 7.36f, 7.58f, 4f, 12f, 4f)
                curveTo(16.42f, 4f, 20f, 7.36f, 20f, 11.5f)
                close()
            }
        }
    }

    // 6. Remote Wand, Trackpad, Laser & Keyboard
    val ArrowRight: ImageVector by lazy {
        strokeIcon("ArrowRight") {
            linePath {
                moveTo(5f, 12f); horizontalLineTo(19f)
                moveTo(12.5f, 5.5f); lineTo(19f, 12f); lineTo(12.5f, 18.5f)
            }
        }
    }

    val Target: ImageVector by lazy {
        strokeIcon("Target") {
            linePath {
                moveTo(19.5f, 12f)
                arcTo(7.5f, 7.5f, 0f, true, true, 4.5f, 12f)
                arcTo(7.5f, 7.5f, 0f, true, true, 19.5f, 12f)
                close()
                moveTo(14.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 12f)
                close()
                moveTo(12f, 2.5f); verticalLineTo(5.2f)
                moveTo(12f, 18.8f); verticalLineTo(21.5f)
                moveTo(2.5f, 12f); horizontalLineTo(5.2f)
                moveTo(18.8f, 12f); horizontalLineTo(21.5f)
            }
        }
    }

    val Keyboard: ImageVector by lazy {
        strokeIcon("Keyboard") {
            linePath {
                moveTo(4.5f, 6f)
                horizontalLineTo(19.5f)
                curveTo(20.6f, 6f, 21.5f, 6.9f, 21.5f, 8f)
                verticalLineTo(16f)
                curveTo(21.5f, 17.1f, 20.6f, 18f, 19.5f, 18f)
                horizontalLineTo(4.5f)
                curveTo(3.4f, 18f, 2.5f, 17.1f, 2.5f, 16f)
                verticalLineTo(8f)
                curveTo(2.5f, 6.9f, 3.4f, 6f, 4.5f, 6f)
                close()
                moveTo(6.5f, 10f); horizontalLineTo(7.5f)
                moveTo(10.5f, 10f); horizontalLineTo(11.5f)
                moveTo(13.5f, 10f); horizontalLineTo(14.5f)
                moveTo(16.5f, 10f); horizontalLineTo(17.5f)
                moveTo(6.5f, 14.2f); horizontalLineTo(7.5f)
                moveTo(9.8f, 14.2f); horizontalLineTo(14.2f)
                moveTo(16.5f, 14.2f); horizontalLineTo(17.5f)
            }
        }
    }

    val Mouse: ImageVector by lazy {
        strokeIcon("Mouse") {
            linePath {
                moveTo(12f, 3.5f)
                curveTo(14.76f, 3.5f, 17f, 5.74f, 17f, 8.5f)
                verticalLineTo(15.5f)
                curveTo(17f, 18.26f, 14.76f, 20.5f, 12f, 20.5f)
                curveTo(9.24f, 20.5f, 7f, 18.26f, 7f, 15.5f)
                verticalLineTo(8.5f)
                curveTo(7f, 5.74f, 9.24f, 3.5f, 12f, 3.5f)
                close()
                moveTo(12f, 7.2f); verticalLineTo(10.6f)
            }
        }
    }

    val Lock: ImageVector by lazy {
        strokeIcon("Lock") {
            linePath {
                moveTo(7f, 11f)
                horizontalLineTo(17f)
                curveTo(18.1f, 11f, 19f, 11.9f, 19f, 13f)
                verticalLineTo(18.5f)
                curveTo(19f, 19.6f, 18.1f, 20.5f, 17f, 20.5f)
                horizontalLineTo(7f)
                curveTo(5.9f, 20.5f, 5f, 19.6f, 5f, 18.5f)
                verticalLineTo(13f)
                curveTo(5f, 11.9f, 5.9f, 11f, 7f, 11f)
                close()
                moveTo(8.5f, 11f)
                verticalLineTo(7.5f)
                curveTo(8.5f, 5.57f, 10.07f, 4f, 12f, 4f)
                curveTo(13.93f, 4f, 15.5f, 5.57f, 15.5f, 7.5f)
                verticalLineTo(11f)
                moveTo(12f, 15f); verticalLineTo(16.8f)
            }
        }
    }
}
