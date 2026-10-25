package app.opaquevault.ui.theme

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
import app.opaquevault.security.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = VaultMint,
    onPrimary = VaultInk,
    primaryContainer = Color(0xFF173D33),
    onPrimaryContainer = Color(0xFFB8F8E2),
    secondary = Color(0xFFA5D8C7),
    secondaryContainer = Color(0xFF29483F),
    onSecondaryContainer = Color(0xFFD5F5E9),
    tertiary = Color(0xFFE0C46B),
    background = VaultInk,
    onBackground = Color(0xFFE2F1EB),
    surface = VaultInk,
    onSurface = Color(0xFFE2F1EB),
    surfaceVariant = Color(0xFF2A3A35),
    surfaceContainerLowest = Color(0xFF040C0A),
    surfaceContainerLow = Color(0xFF0E1A17),
    surfaceContainer = VaultSurface,
    surfaceContainerHigh = VaultSurfaceRaised,
    surfaceContainerHighest = Color(0xFF22342E),
    onSurfaceVariant = Color(0xFFB5CAC2),
    outline = Color(0xFF678078),
    outlineVariant = Color(0xFF3B4D47),
    error = VaultWarning,
    errorContainer = Color(0xFF5D1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = VaultMintDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F8E2),
    onPrimaryContainer = Color(0xFF002117),
    secondary = VaultSlate,
    secondaryContainer = Color(0xFFD3E8E0),
    onSecondaryContainer = Color(0xFF102F27),
    tertiary = Color(0xFF685D22),
    background = VaultPaper,
    onBackground = Color(0xFF10201B),
    surface = VaultPaper,
    onSurface = Color(0xFF10201B),
    surfaceVariant = Color(0xFFDBE5E0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF6F2),
    surfaceContainer = VaultPaperSurface,
    surfaceContainerHigh = Color(0xFFE1ECE7),
    surfaceContainerHighest = Color(0xFFDBE6E1),
    onSurfaceVariant = VaultSlate,
    outline = Color(0xFF71857E),
    outlineVariant = Color(0xFFBFCAC5),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun OpaqueVaultTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
