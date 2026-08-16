package com.dimroom.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One labelled adjustment.
 *
 * [onChange] fires continuously for a live preview while [onChangeFinished] closes a single undo
 * entry, so a drag reads back as one step in history rather than hundreds.
 */
@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -100f..100f,
    decimals: Int = 0,
    onGestureStart: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatValue(value, decimals),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (value == 0f) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = { next ->
                onGestureStart()
                onChange(next)
            },
            onValueChangeFinished = onChangeFinished,
        )
    }
}

private fun formatValue(value: Float, decimals: Int): String = when {
    decimals <= 0 -> value.roundToInt().toString()
    else -> {
        val factor = when (decimals) {
            1 -> 10f
            2 -> 100f
            else -> 1000f
        }
        ((value * factor).roundToInt() / factor).toString()
    }
}
