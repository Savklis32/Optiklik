package com.optiklik.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionScoresGrid(
    scores: List<Int>,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        (0 until 10).forEach { idx ->
            val score = scores.getOrElse(idx) { 0 }
            val isZero = score == 0
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (isZero) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "S${idx + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isZero) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    Text(
                        text = score.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isZero) FontWeight.Normal else FontWeight.Bold,
                        color = if (isZero) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }
    }
}
