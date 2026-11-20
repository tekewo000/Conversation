package jp.tekewo.conversation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val conversationColors = darkColorScheme(
    background = Color(0xFF101821),
    onBackground = Color(0xFFF4F1EB),
    surface = Color(0xFF182530),
    onSurface = Color(0xFFF4F1EB),
    surfaceVariant = Color(0xFF263844),
    onSurfaceVariant = Color(0xFFBBC9CE),
    primary = Color(0xFFF4CE91),
    onPrimary = Color(0xFF2B1D0D),
    primaryContainer = Color(0xFF453622),
    onPrimaryContainer = Color(0xFFFFDEA9),
    secondaryContainer = Color(0xFF264A49),
    onSecondaryContainer = Color.White,
    outline = Color(0xFF82969F),
    outlineVariant = Color(0xFF354A57),
    error = Color(0xFFFFB4AB),
)

internal fun messageBubbleColor(user: Boolean) =
    if (user) conversationColors.secondaryContainer else conversationColors.surface

internal fun messageBubbleShape(user: Boolean) = RoundedCornerShape(
    topStart = if (user) 16.dp else 4.dp,
    topEnd = if (user) 4.dp else 16.dp,
    bottomStart = 16.dp,
    bottomEnd = 16.dp,
)
