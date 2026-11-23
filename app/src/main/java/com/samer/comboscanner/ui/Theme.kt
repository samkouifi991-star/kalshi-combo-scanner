package com.samer.comboscanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Positive = Color(0xFF0E8A5F)
val PositiveDark = Color(0xFF4CC38A)
val Negative = Color(0xFFC0392B)
val Warning = Color(0xFFB7791F)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2E8),
    onPrimaryContainer = Color(0xFF00382C),
    secondary = Color(0xFF3F5E58),
    surface = Color(0xFFFBFCFB),
    surfaceVariant = Color(0xFFE6ECEA),
    background = Color(0xFFF4F7F6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FD8B8),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0B4F41),
    onPrimaryContainer = Color(0xFFD3F2E8),
    secondary = Color(0xFFA9CBC3),
    surface = Color(0xFF151A19),
    surfaceVariant = Color(0xFF2A3230),
    background = Color(0xFF0F1312),
)

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

@Composable
fun positiveColor() = if (isSystemInDarkTheme()) PositiveDark else Positive
