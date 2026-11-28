package com.wearos.ancsbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.model.PhoneInfo
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

internal fun weatherIcon(code: Int, day: Boolean = true): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    2 -> Icons.Rounded.WbCloudy
    3 -> Icons.Rounded.Cloud
    45, 48 -> Icons.Rounded.Dehaze
    51, 53, 55, 56, 57 -> Icons.Rounded.Grain
    61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Rounded.Umbrella
    71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

internal fun weatherTint(code: Int, day: Boolean = true): Color = when (code) {
    0, 1 -> if (day) Color(0xFFFFD60A) else Color(0xFFBF5AF2)
    in 51..67, in 80..82 -> Color(0xFF64D2FF)
    in 95..99 -> Color(0xFFFFD60A)
    else -> Color(0xFFD1D1D6)
}

private fun clock(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun eventTime(event: PhoneInfo.Event, now: Long): String {
    if (event.allDay) return "All day"
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val start = Calendar.getInstance().apply { timeInMillis = event.startMs }
    val prefix = if (start.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) "Tomorrow " else ""
    return when {
        event.startMs <= now && event.endMs > now -> "Now · until ${clock(event.endMs)}"
        else -> prefix + clock(event.startMs)
    }
}

@Composable
internal fun TodayScreen(viewModel: com.wearos.ancsbridge.viewmodel.MainViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PhoneInfo.load(context); viewModel.refreshPhoneInfo() }
    val info by PhoneInfo.current.collectAsState()
    TodayContent(info, onComplete = viewModel::completeReminder)
}

@Composable
internal fun TodayContent(info: PhoneInfo, onComplete: (String) -> Unit, now: Long = System.currentTimeMillis()) {
    ListScreen {
        item { ListHeader { Text("Today") } }
        val weather = info.weather
        if (weather != null) item { WeatherCard(weather) }
        item { Text("CALENDAR", style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F)) }
        val upcoming = info.events.filter { it.endMs > now }
        if (upcoming.isEmpty()) item {
            Text("No more events today", style = MaterialTheme.typography.bodySmall, color = Color(0xFF98989F))
        }
        upcoming.forEach { event ->
            item(key = "e" + event.startMs + event.title) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1C1C1E))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp))
                        .background(event.color?.let { Color(it) } ?: Color(0xFFFF453A)))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(event.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(eventTime(event, now) + if (event.location.isNotBlank()) " · ${event.location}" else "",
                            fontSize = 11.sp, color = Color(0xFF98989F), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (info.reminders.isNotEmpty()) {
            item { Text("REMINDERS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F)) }
            info.reminders.forEach { reminder ->
                item(key = "r" + reminder.id) {
                    Button(
                        onClick = { onComplete(reminder.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF3A3A3C)).padding(2.dp)) {
                                Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF1C1C1E)))
                            }
                        },
                        secondaryLabel = reminder.dueMs?.let { due ->
                            { Text(if (due < now) "Overdue · ${clock(due)}" else clock(due),
                                color = if (due < now) Color(0xFFFF453A) else Color(0xFF98989F)) }
                        },
                    ) { Text(reminder.title, maxLines = 2) }
                }
            }
        }
        if (info.atMs == 0L) item {
            Text("Turn on Weather, Calendar and Reminders in the WearBridge iPhone app.",
                style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun WeatherCard(weather: PhoneInfo.Weather) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(if (weather.day) Color(0xFF15365C) else Color(0xFF1B1B3A))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(weather.place.ifBlank { "Weather" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${weather.temp.roundToInt()}°", fontSize = 36.sp, fontWeight = FontWeight.Light)
            }
            Icon(weatherIcon(weather.code, weather.day), contentDescription = weather.condition,
                tint = weatherTint(weather.code, weather.day), modifier = Modifier.size(34.dp))
        }
        Text(
            weather.condition + (if (weather.hi != null && weather.lo != null)
                "  H:${weather.hi.roundToInt()}° L:${weather.lo.roundToInt()}°" else ""),
            fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1
        )
        if (weather.hourly.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weather.hourly.take(5).forEachIndexed { index, (time, temp, code) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (index == 0) "Now" else Calendar.getInstance().apply { timeInMillis = time }
                            .get(Calendar.HOUR_OF_DAY).toString(), fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                        Icon(weatherIcon(code), contentDescription = null, tint = weatherTint(code), modifier = Modifier.size(14.dp))
                        Text("${temp.roundToInt()}°", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TodaySummaryButton(info: PhoneInfo, now: Long = System.currentTimeMillis(), onClick: () -> Unit) {
    val weather = info.weather
    val next = info.nextEvent(now)
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = {
            if (weather != null) Icon(weatherIcon(weather.code, weather.day), contentDescription = null,
                tint = weatherTint(weather.code, weather.day))
            else IconBadge(Icons.Rounded.WbSunny, Color(0xFFFF9F0A))
        },
        secondaryLabel = {
            Text(next?.let { "${eventTime(it, now)} ${it.title}" } ?: "Weather, calendar, reminders",
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) { Text(weather?.let { "${it.temp.roundToInt()}° ${it.condition}" } ?: "Today", maxLines = 1) }
}
