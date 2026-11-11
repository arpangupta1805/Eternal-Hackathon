package com.eternallite.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eternallite.app.AppContainer
import kotlinx.coroutines.delay

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

// Eternal-like red accent. No real brand artwork.
val BrandRed = Color(0xFFE23744)
val OkGreen = Color(0xFF14784A)
val WarnAmber = Color(0xFF96590A)
val Muted = Color(0xFF56606D)

private val Colors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    secondary = Color(0xFF14171C),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onBackground = Color(0xFF14171C),
    onSurface = Color(0xFF14171C),
    error = Color(0xFFB81F2C),
)

@Composable
fun EternalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

/** Paise to a rupee string: 14600 -> "₹146", 3350 -> "₹33.50". */
fun rupees(paise: Int): String {
    val whole = paise / 100
    val part = paise % 100
    return if (part == 0) "₹$whole" else "₹$whole.${part.toString().padStart(2, '0')}"
}

fun agoText(thenMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "1 min ago"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ${minutes % 60} min ago"
    }
}

/** Current time that recomposes on a fixed interval. */
@Composable
fun tickingNow(intervalMs: Long): Long {
    val clock = LocalContainer.current.clock
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = clock()
        }
    }
    return now
}

/**
 * Always-visible status strip (docs/05-android-app.md, Home):
 * online or Lite mode, relays nearby, and how old the menu is.
 */
@Composable
fun StatusStrip() {
    val container = LocalContainer.current
    val online by container.connectivity.isOnline.collectAsState()
    val menuAsOf by container.catalog.menuAsOf.collectAsState(initial = null)
    val now = tickingNow(15_000)
    val relays = 0 // wired to the mesh in M5

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (online) OkGreen else BrandRed)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            if (online) "Online" else "Lite mode: no internet. Orders will be relayed.",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        val relayText = if (online) null else if (relays == 1) "1 relay nearby" else "$relays relays nearby"
        val menuText = menuAsOf?.let { "Menu as of ${agoText(it, now)}" }
        val second = listOfNotNull(relayText, menuText).joinToString(". ")
        if (second.isNotEmpty()) {
            Text(second, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Screen frame: status strip, a title row with optional back and action, then the content. */
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    showStatus: Boolean = true,
    bottomBar: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (showStatus) StatusStrip()
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp, top = 8.dp, bottom = 8.dp),
                )
                if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (bottomBar != null) {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) { bottomBar() }
                }
            }
        }
    }
}
