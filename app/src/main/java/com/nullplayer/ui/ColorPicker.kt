package com.nullplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.data.Group
import android.graphics.Color as AndroidColor

/**
 * A hue strip over a saturation/value field, plus the house palette as shortcuts.
 *
 * HSV rather than three RGB sliders because picking a colour is a visual act: you point at the one
 * you want. The palette stays because most people want one of eight sensible colours and should
 * not have to aim for it.
 *
 * Hue, saturation and value are held here rather than derived from [color] on every frame. A
 * fully black or fully unsaturated colour has no meaningful hue to read back, so round-tripping
 * through the packed int would make the cursor jump the moment a drag reached an edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPicker(
    color: Int,
    onColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember { FloatArray(3).also { AndroidColor.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var emitted by remember { mutableIntStateOf(color) }

    // Follow the palette shortcuts, which change [color] from outside the field.
    LaunchedEffect(color) {
        if (color != emitted) {
            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
            hue = hsv[0]
            saturation = hsv[1]
            value = hsv[2]
            emitted = color
        }
    }

    fun emit() {
        val packed = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
        emitted = packed
        onColor(packed)
    }

    val current = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(current)
                    .border(1.dp, LINE, RoundedCornerShape(9.dp))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "#%06X".format(0xFFFFFF and emitted),
                color = MUTED,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Saturation across, value down.
        Box(
            Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, LINE, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    fun report(position: Offset) {
                        saturation = (position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                    detectTapGestures { report(it) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val pure = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

                val cursor = Offset(size.width * saturation, size.height * (1f - value))
                drawCircle(Color.White, radius = 8.dp.toPx(), center = cursor, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.5f), radius = 10.dp.toPx(), center = cursor, style = Stroke(1.dp.toPx()))
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .border(1.dp, LINE, RoundedCornerShape(13.dp))
                .pointerInput(Unit) {
                    fun report(x: Float) {
                        hue = (x / size.width).coerceIn(0f, 1f) * 360f
                        emit()
                    }
                    detectTapGestures { report(it.x) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                        emit()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(HUES))
                val x = size.width * (hue / 360f)
                drawCircle(Color.White, radius = size.height / 2.4f, center = Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
            }
        }

        Spacer(Modifier.height(14.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Group.PALETTE.forEach { option ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(option))
                        .border(
                            width = if (option == emitted) 3.dp else 0.dp,
                            color = TEXT,
                            shape = CircleShape,
                        )
                        .clickable { onColor(option) },
                )
            }
        }
    }
}

/** The full wheel, laid out flat. */
private val HUES = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)
