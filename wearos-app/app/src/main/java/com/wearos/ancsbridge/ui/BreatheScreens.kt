package com.wearos.ancsbridge.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.health.HealthOutbox
import com.wearos.ancsbridge.health.PassiveRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BreatheTeal = Color(0xFF5AC8FA)
private val BreatheMint = Color(0xFF63E6BE)
private const val INHALE_MS = 4_000
private const val EXHALE_MS = 6_000

/**
 * Guided breathing like Apple Watch's Mindfulness app: about six breaths a minute, light taps
 * while breathing in. Sessions are saved to Apple Health as Mindful Minutes.
 */
@Composable
internal fun BreatheScreen(onSaved: () -> Unit) {
    var minutes by remember { mutableIntStateOf(0) }
    var finishedMs by remember { mutableLongStateOf(-1L) }
    when {
        finishedMs >= 0 -> BreatheDone(finishedMs) { finishedMs = -1; minutes = 0 }
        minutes > 0 -> BreatheSession(minutes) { elapsed ->
            finishedMs = elapsed
            if (elapsed >= 30_000) onSaved()
        }
        else -> BreathePicker { minutes = it }
    }
}

@Composable
internal fun BreathePicker(onStart: (Int) -> Unit) {
    ListScreen {
        item { ListHeader { Text("Breathe") } }
        item {
            Text(
                "Breathe in as the circle grows, out as it shrinks.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
        listOf(1, 2, 3, 5).forEach { length ->
            item(key = length) {
                NavButton(Icons.Rounded.SelfImprovement, BreatheTeal, "$length min",
                    if (length == 1) "About 6 breaths" else null) { onStart(length) }
            }
        }
        item {
            Text(
                "Saved to Apple Health as Mindful Minutes.",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun BreatheSession(minutes: Int, onFinish: (Long) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scale = remember { Animatable(0.45f) }
    var inhaling by remember { mutableStateOf(true) }
    var remainingMs by remember { mutableLongStateOf(minutes * 60_000L) }
    val startMs = remember { System.currentTimeMillis() }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            // Leaving early still counts once it lasted half a minute.
            val elapsed = System.currentTimeMillis() - startMs
            if (remainingMs > 0 && elapsed >= 30_000) saveMindful(context, startMs, System.currentTimeMillis())
        }
    }
    LaunchedEffect(Unit) {
        launch {
            while (remainingMs > 0) {
                delay(250)
                remainingMs = (minutes * 60_000L - (System.currentTimeMillis() - startMs)).coerceAtLeast(0)
            }
        }
        delay(600)
        while (System.currentTimeMillis() - startMs < minutes * 60_000L) {
            inhaling = true
            tapsWhileInhaling(context)
            scale.animateTo(1f, tween(INHALE_MS, easing = FastOutSlowInEasing))
            inhaling = false
            scale.animateTo(0.45f, tween(EXHALE_MS, easing = FastOutSlowInEasing))
        }
        val end = System.currentTimeMillis()
        saveMindful(context, startMs, end)
        remainingMs = 0
        onFinish(end - startMs)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BreatheFlower(scale.value)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 120.dp)) {
            Text(if (inhaling) "Breathe in" else "Breathe out", style = MaterialTheme.typography.titleMedium)
            Text(formatTimer(remainingMs, hundredths = false), style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF98989F))
        }
    }
}

/** Overlapping petals that open and close, in the spirit of Apple's Breathe animation. */
@Composable
internal fun BreatheFlower(scale: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(150.dp)) {
        val radius = size.minDimension / 4f * scale
        val spread = radius * 0.95f
        for (i in 0 until 6) {
            val angle = Math.toRadians((i * 60.0) + scale * 45.0)
            val center = Offset(
                this.center.x + (spread * kotlin.math.cos(angle)).toFloat(),
                this.center.y + (spread * kotlin.math.sin(angle)).toFloat(),
            )
            drawCircle(
                Brush.radialGradient(listOf(BreatheMint.copy(alpha = 0.55f), BreatheTeal.copy(alpha = 0.35f)), center, radius),
                radius, center
            )
        }
        drawCircle(Color.White.copy(alpha = 0.12f), radius * 2.1f, this.center, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun BreatheDone(elapsedMs: Long, onAgain: () -> Unit) {
    ListScreen {
        item { BreatheFlower(0.6f, Modifier.height(90.dp)) }
        item { ListHeader { Text("Well done") } }
        item {
            Text(
                if (elapsedMs >= 30_000) "${(elapsedMs + 30_000) / 60_000} min · saved to Apple Health"
                else "Too short to save",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.fillMaxWidth()
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Breathe Again") }
        }
    }
}

private fun saveMindful(context: Context, startMs: Long, endMs: Long) {
    if (endMs - startMs < 30_000) return
    runCatching { HealthOutbox(context).add(PassiveRecord.mindfulJson(startMs to endMs)) }
}

private fun tapsWhileInhaling(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    // Light taps through the in-breath, quiet on the out-breath.
    val timings = LongArray(16) { if (it % 2 == 0) 230L else 20L }
    val amplitudes = IntArray(16) { if (it % 2 == 0) 0 else 70 }
    runCatching { vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
}
