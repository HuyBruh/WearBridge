package com.wearos.ancsbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.health.PassiveHealthService
import com.wearos.ancsbridge.health.Rings
import com.wearos.ancsbridge.viewmodel.MainViewModel

internal object RingColors {
    val Move = Color(0xFFFA114F)
    val Exercise = Color(0xFFA6FF00)
    val Stand = Color(0xFF00F0FF)
}

/** Three concentric Activity rings; laps past 100% keep going like on Apple Watch. */
@Composable
internal fun ActivityRings(rings: Rings, size: Dp, stroke: Dp = size / 9) {
    Canvas(Modifier.size(size)) {
        val width = stroke.toPx()
        val gap = width * 0.18f
        listOf(
            rings.move to RingColors.Move,
            rings.exercise to RingColors.Exercise,
            rings.stand to RingColors.Stand,
        ).forEachIndexed { index, (progress, color) ->
            val inset = width / 2 + index * (width + gap)
            val topLeft = Offset(inset, inset)
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            val sweep = (progress.coerceAtLeast(0f) * 360f).coerceAtMost(720f)
            if (sweep > 0f) {
                drawArc(color, -90f, sweep.coerceAtLeast(2f), false, topLeft, arcSize,
                    style = Stroke(width, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
internal fun ActivitySummaryButton(rings: Rings, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { ActivityRings(rings, 28.dp, 4.dp) },
        secondaryLabel = {
            Text(
                if (rings.hasData) "${rings.moveKcal} kcal · ${rings.exerciseMin}m · ${rings.standHours}h"
                else "Move, Exercise, Stand",
                maxLines = 1
            )
        },
    ) { Text("Activity") }
}

@Composable
internal fun ActivityScreen(viewModel: MainViewModel) {
    LaunchedEffect(Unit) { viewModel.activityRingsNow() }
    val rings by viewModel.activityRings.collectAsState()
    var enabled by remember { mutableStateOf(viewModel.passiveHealthEnabled()) }
    ActivityContent(
        rings = rings,
        monitoring = enabled,
        onEnable = { viewModel.setPassiveHealth(true) { enabled = it } },
        onGoals = viewModel::setActivityGoals,
    )
}

@Composable
internal fun ActivityContent(
    rings: Rings,
    monitoring: Boolean,
    onEnable: () -> Unit,
    onGoals: (Int, Int, Int) -> Unit,
) {
    ListScreen {
        item {
            Box(Modifier.padding(top = 20.dp), contentAlignment = Alignment.Center) {
                ActivityRings(rings, 118.dp)
            }
        }
        item { RingStat("MOVE", "${rings.moveKcal}/${rings.moveGoal}", "KCAL", RingColors.Move) }
        item { RingStat("EXERCISE", "${rings.exerciseMin}/${rings.exerciseGoal}", "MIN", RingColors.Exercise) }
        item { RingStat("STAND", "${rings.standHours}/${rings.standGoal}", "HRS", RingColors.Stand) }
        item {
            Text("${rings.steps} steps today", style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF98989F), modifier = Modifier.padding(top = 4.dp))
        }
        if (!monitoring) item {
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text("Turn On Activity") }
        }
        item { ListHeader { Text("Goals") } }
        item {
            GoalStepper("Move", "${rings.moveGoal} kcal", RingColors.Move,
                onMinus = { onGoals(rings.moveGoal - 50, rings.exerciseGoal, rings.standGoal) },
                onPlus = { onGoals(rings.moveGoal + 50, rings.exerciseGoal, rings.standGoal) })
        }
        item {
            GoalStepper("Exercise", "${rings.exerciseGoal} min", RingColors.Exercise,
                onMinus = { onGoals(rings.moveGoal, rings.exerciseGoal - 5, rings.standGoal) },
                onPlus = { onGoals(rings.moveGoal, rings.exerciseGoal + 5, rings.standGoal) })
        }
        item {
            GoalStepper("Stand", "${rings.standGoal} h", RingColors.Stand,
                onMinus = { onGoals(rings.moveGoal, rings.exerciseGoal, rings.standGoal - 1) },
                onPlus = { onGoals(rings.moveGoal, rings.exerciseGoal, rings.standGoal + 1) })
        }
        item {
            Text(
                "Move counts active calories, Exercise counts workouts and brisk walking, Stand counts hours you moved around.",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun RingStat(label: String, value: String, unit: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = color)
            Spacer(Modifier.width(3.dp))
            Text(unit, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun GoalStepper(label: String, value: String, color: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label goal")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = color)
        }
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = "Increase $label goal")
        }
    }
}
