package com.mootmaker.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own icons, where the webapp draws a custom one rather than a stock Material icon, so
 * both frontends show the same mark. Each is ported path for path from the webapp's
 * `webapp/src/icons/index.tsx`. Filled black, so `Icon` tints them with `LocalContentColor`.
 */
object MootmakerIcons {
    /**
     * A 4-point sparkle with a smaller companion at 60% opacity: the webapp's `SparkleIcon`, used
     * on the "Suggest a room" button.
     */
    val Sparkle: ImageVector by lazy {
        ImageVector.Builder(
            name = "MootmakerSparkle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(12.5f, 7f, 13f, 9.5f, 18f, 10f)
                curveTo(13f, 10.5f, 12.5f, 13f, 12f, 18f)
                curveTo(11.5f, 13f, 11f, 10.5f, 6f, 10f)
                curveTo(11f, 9.5f, 11.5f, 7f, 12f, 2f)
                close()
            }
            path(fill = SolidColor(Color.Black), fillAlpha = 0.6f) {
                moveTo(18.5f, 13f)
                curveTo(18.75f, 14.6f, 18.9f, 15.25f, 20.5f, 15.5f)
                curveTo(18.9f, 15.75f, 18.75f, 16.4f, 18.5f, 18f)
                curveTo(18.25f, 16.4f, 18.1f, 15.75f, 16.5f, 15.5f)
                curveTo(18.1f, 15.25f, 18.25f, 14.6f, 18.5f, 13f)
                close()
            }
        }.build()
    }
}
