package com.wearos.ancsbridge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.messages.Avatars

private val initialsColors = listOf(
    Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFBF5AF2),
    Color(0xFFFF375F), Color(0xFF64D2FF), Color(0xFFFFD60A), Color(0xFF5E5CE6),
)

/** The contact's photo from the iPhone, or coloured initials like Apple's Messages. */
@Composable
fun ContactAvatar(name: String, address: String? = null, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photo = remember(name, address) { runCatching { Avatars.find(context, name, address) }.getOrNull() }
    if (photo != null) {
        Image(
            bitmap = photo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape)
        )
        return
    }
    val initials = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
        .take(2).joinToString("") { it.first().uppercase() }
        .ifEmpty { "#" }
    val color = initialsColors[(name.hashCode() and Int.MAX_VALUE) % initialsColors.size]
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.65f)))),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}
