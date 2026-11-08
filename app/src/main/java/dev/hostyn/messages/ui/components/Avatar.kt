package dev.hostyn.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

@Composable
fun avatarColors(seed: Long): Pair<Color, Color> = avatarColors(MaterialTheme.colorScheme, seed)

/** Same color choice inside and outside Compose (e.g. in the call notification). */
fun avatarColors(c: ColorScheme, seed: Long): Pair<Color, Color> {
    val options = listOf(
        c.primaryContainer to c.onPrimaryContainer,
        c.secondaryContainer to c.onSecondaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer,
    )
    return options[Math.floorMod(seed, options.size.toLong()).toInt()]
}

fun initialOf(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"

@Composable
fun ContactAvatar(
    name: String,
    photoUri: String?,
    seed: Long,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val (bg, fg) = avatarColors(seed)
    val placeholder = @Composable {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            val initial = initialOf(name)
            if (initial.first().isLetter()) {
                Text(
                    initial,
                    color = fg,
                    fontWeight = FontWeight.Medium,
                    fontSize = (size.value * 0.42f).sp,
                )
            } else {
                Icon(Icons.Rounded.Person, null, tint = fg, modifier = Modifier.size(size * 0.55f))
            }
        }
    }
    Box(modifier.size(size)) {
        if (photoUri == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}
