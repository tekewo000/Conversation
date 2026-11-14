package jp.tekewo.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object ConversationIcons {
    val Add = strokeVector("ConversationAdd", 1.8f) {
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(12f, 5f); lineTo(12f, 19f)
    }
    val Play = strokeVector("ConversationPlay", 1.8f) {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
    }
    val Menu = strokeVector("ConversationMenu", 1.8f) {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
    }
    val Send = strokeVector("ConversationSend", 1.8f) {
        moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
        moveTo(12f, 5f); lineTo(12f, 20f)
    }
    private val menuIcons = List(7) { kind ->
        strokeVector("ConversationMenu$kind", 1.65f) {
            when (kind) {
                0 -> {
                    moveTo(5f, 4f); lineTo(19f, 4f); lineTo(21f, 6f)
                    lineTo(21f, 15f); lineTo(19f, 17f); lineTo(9f, 17f)
                    lineTo(4f, 21f); lineTo(4f, 6f); close()
                }
                1 -> {
                    moveTo(7f, 3f); lineTo(17f, 3f)
                    arcTo(2f, 2f, 0f, false, true, 19f, 5f)
                    lineTo(19f, 19f); arcTo(2f, 2f, 0f, false, true, 17f, 21f)
                    lineTo(7f, 21f); arcTo(2f, 2f, 0f, false, true, 5f, 19f)
                    lineTo(5f, 5f); arcTo(2f, 2f, 0f, false, true, 7f, 3f); close()
                    for (y in 8..16 step 4) { moveTo(9f, y.toFloat()); lineTo(15f, y.toFloat()) }
                }
                2, 3 -> {
                    moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f)
                    lineTo(12f, 17f); lineTo(6f, 21f); close()
                    if (kind == 3) {
                        moveTo(9f, 9f); lineTo(15f, 9f)
                        moveTo(12f, 6f); lineTo(12f, 12f)
                    }
                }
                4 -> {
                    for (i in 0..2) {
                        val y = (6 + i * 6).toFloat()
                        val x = if (i == 1) 15f else 9f
                        moveTo(4f, y); lineTo(x - 2f, y)
                        moveTo(x + 2f, y); lineTo(20f, y)
                        circle(x, y, 2f)
                    }
                }
                5 -> {
                    circle(12f, 12f, 8f)
                    moveTo(12f, 11f); lineTo(12f, 16f)
                    moveTo(12f, 7.5f); lineTo(12f, 7.5f)
                }
                else -> {
                    moveTo(6f, 6f); lineTo(18f, 18f)
                    moveTo(18f, 6f); lineTo(6f, 18f)
                }
            }
        }
    }

    fun menu(kind: Int): ImageVector = menuIcons.getOrElse(kind) { menuIcons[6] }

    private fun strokeVector(name: String, width: Float, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
        moveTo(x - radius, y)
        arcTo(radius, radius, 0f, false, true, x + radius, y)
        arcTo(radius, radius, 0f, false, true, x - radius, y)
        close()
    }
}
