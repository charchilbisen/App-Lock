package com.example.ui.theme

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NightlightRound

data class ThemeColors(
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val onSurface: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val cardBg: Color,
    val accent: Color
)

@Composable
fun getThemeColors(): ThemeColors {
    val b = MaterialTheme.colorScheme.background
    val isDark = b == HDPrimaryDark
    return ThemeColors(
        background = b,
        surface = MaterialTheme.colorScheme.surface,
        onBackground = MaterialTheme.colorScheme.onBackground,
        onSurface = MaterialTheme.colorScheme.onSurface,
        primary = MaterialTheme.colorScheme.primary,
        onPrimary = MaterialTheme.colorScheme.onPrimary,
        primaryContainer = MaterialTheme.colorScheme.primaryContainer,
        onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer,
        textPrimary = MaterialTheme.colorScheme.onBackground,
        textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
        border = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
        cardBg = if (isDark) Color(0xFF1E2230) else Color(0xFFFFFFFF),
        accent = HDAccentLightBlue
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = HDAccentLightBlue,
    onPrimary = HDPrimaryDark,
    primaryContainer = HDPrimaryDark,
    onPrimaryContainer = HDAccentLightBlue,
    secondary = HDAccentLightBlue,
    background = HDPrimaryDark,
    surface = Color(0xFF1E2230),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)

private val LightColorScheme = lightColorScheme(
    primary = HDPrimaryDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = HDAccentLightBlue,
    onPrimaryContainer = HDPrimaryDark,
    secondary = HDPrimaryDark,
    background = HDBackground,
    surface = HDCardContainer,
    onBackground = HDTextPrimary,
    onSurface = HDTextPrimary
)

@Composable
fun MyApplicationTheme(
    themeMode: String = "SYSTEM",
    darkTheme: Boolean = when (themeMode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = false, // Set to false to force our beautiful High Density theme
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun ThemeSelectorRow(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val options = listOf(
            Triple("LIGHT", Icons.Filled.WbSunny, "Light"),
            Triple("SYSTEM", Icons.Filled.Settings, "System"),
            Triple("DARK", Icons.Filled.NightlightRound, "Dark")
        )
        options.forEach { (mode, icon, desc) ->
            val isSelected = currentTheme == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onThemeSelected(mode) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("theme_btn_$mode"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = desc,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

