package com.rmx5070.onekey.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F4BE),
    onPrimaryContainer = Color(0xFF00210D),
    secondary = Color(0xFF4F6354),
    tertiary = Color(0xFF3A6470),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BD7A0),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF005228),
    onPrimaryContainer = Color(0xFFA6F4BE),
    secondary = Color(0xFFB6CCB9),
    tertiary = Color(0xFFA2CDDC),
    error = Color(0xFFFFB4AB),
)

@Composable
fun Rmx5070Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
