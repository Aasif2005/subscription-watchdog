package com.aasif.watchdog.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The few icons the app needs, drawn as 24-unit stroke paths (the same ones as the design canvas) instead of
 * pulling in the large material-icons-extended artifact. Tint them with `Icon(tint = ...)`.
 */
object WatchdogIcons {
    private fun icon(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private const val CIRCLE = "M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z"

    val Shield by lazy { icon("Shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M8.5 12l2.5 2.5 4.5-5") }
    val Back by lazy { icon("Back", "M15 6l-6 6 6 6") }
    val Close by lazy { icon("Close", "M6 6l12 12M18 6L6 18") }
    val Refresh by lazy { icon("Refresh", "M20 12a8 8 0 1 1-2.34-5.66", "M20 4v5h-5") }
    val Spinner by lazy { icon("Spinner", "M20 12a8 8 0 1 1-8-8", width = 2.2f) }
    val Add by lazy { icon("Add", "M12 5v14M5 12h14", width = 2.2f) }
    val Check by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5", width = 2.4f) }
    val Mail by lazy { icon("Mail", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 7l9 6 9-6") }
    val Sms by lazy { icon("Sms", "M4 5h16v11H9l-5 4z", "M8 9.5h8M8 12.5h5") }
    val Card by lazy { icon("Card", "M5 6h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M3 10h18M7 15h4") }
    val Bell by lazy { icon("Bell", "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z", "M10 20a2 2 0 0 0 4 0") }
    val Clock by lazy { icon("Clock", CIRCLE, "M12 8v4l3 2") }
    val Lock by lazy { icon("Lock", "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3") }
    val Warning by lazy { icon("Warning", "M12 4l9 16H3z", "M12 10v4M12 17v.5") }
    val Alert by lazy { icon("Alert", CIRCLE, "M12 8v5M12 16v.5") }
    val WifiOff by lazy {
        icon("WifiOff", "M3 3l18 18", "M8.5 16.5a5 5 0 0 1 7 0", "M5 12.8a10 10 0 0 1 4.2-2.4M14.8 10.4A10 10 0 0 1 19 12.8", "M12 20h.01")
    }
    val ChevronRight by lazy { icon("ChevronRight", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { icon("ChevronDown", "M6 9l6 6 6-6") }
    val Calendar by lazy { icon("Calendar", "M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M4 10h16M9 3v4M15 3v4") }
    val External by lazy { icon("External", "M14 4h6v6M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5") }
    val TrendUp by lazy { icon("TrendUp", "M4 17l6-6 4 4 6-7", "M15 8h5v5") }
    val Question by lazy { icon("Question", CIRCLE, "M9.8 9.5a2.3 2.3 0 0 1 4.4.9c0 1.5-2.2 1.9-2.2 3.1M12 16.5v.3") }
    val Cancelled by lazy { icon("Cancelled", CIRCLE, "M9.5 9.5l5 5M14.5 9.5l-5 5") }
    val Swap by lazy { icon("Swap", "M7 7h12l-3-3M17 17H5l3 3") }
}
