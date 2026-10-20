package com.twboxradar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF455A64),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
