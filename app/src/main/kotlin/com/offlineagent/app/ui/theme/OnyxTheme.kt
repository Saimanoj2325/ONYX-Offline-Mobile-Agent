package com.offlineagent.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineagent.core.agents.AgentRole
import com.offlineagent.core.agents.Risk

/** ONYX brand palette (used when dynamic colour is off or unavailable, i.e. Android < 12). Dark-first. */
private val OnyxDark = darkColorScheme(
    primary = Color(0xFFB8C4FF), onPrimary = Color(0xFF0B1A5C), primaryContainer = Color(0xFF26337A), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF8FD8C7), onSecondary = Color(0xFF00382F), secondaryContainer = Color(0xFF0E4F45), onSecondaryContainer = Color(0xFFABF4E2),
    tertiary = Color(0xFFF2B8E4), onTertiary = Color(0xFF4F1A46), tertiaryContainer = Color(0xFF69315E),
    background = Color(0xFF0D0E12), onBackground = Color(0xFFE4E2E9), surface = Color(0xFF0D0E12), onSurface = Color(0xFFE4E2E9),
    surfaceVariant = Color(0xFF2A2C35), onSurfaceVariant = Color(0xFFC5C6D2), outline = Color(0xFF8F909C), outlineVariant = Color(0xFF3B3D48),
    surfaceContainerLowest = Color(0xFF08090C), surfaceContainerLow = Color(0xFF15161B), surfaceContainer = Color(0xFF191A20),
    surfaceContainerHigh = Color(0xFF22232A), surfaceContainerHighest = Color(0xFF2D2E36), error = Color(0xFFFFB4AB),
)
private val OnyxLight = lightColorScheme(
    primary = Color(0xFF3A4A9A), primaryContainer = Color(0xFFDDE1FF), secondary = Color(0xFF00695A), secondaryContainer = Color(0xFFA8F2DF),
    tertiary = Color(0xFF85457A), background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF),
)

private val Base = Typography()
/** Tighter, bolder headlines; comfortable body line height for long answers. */
val OnyxTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

val OnyxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

/** [theme]: DARK (default) | LIGHT | SYSTEM. Dynamic colour (Material You) on Android 12+ when [dynamic]. */
@Composable
fun OnyxTheme(theme: String = "DARK", dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (theme) { "LIGHT" -> false; "SYSTEM" -> isSystemInDarkTheme(); else -> true }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> OnyxDark
        else -> OnyxLight
    }
    MaterialTheme(colorScheme = scheme, typography = OnyxTypography, shapes = OnyxShapes, content = content)
}

/** Stable accent per agent (timeline dots/chips), readable on dark and light surfaces. */
fun agentColor(role: AgentRole): Color = when (role) {
    AgentRole.ORCHESTRATOR -> Color(0xFF9FA8FF)
    AgentRole.PLANNER -> Color(0xFFFFB86B)
    AgentRole.MEMORY -> Color(0xFFE59BFF)
    AgentRole.CONTEXT -> Color(0xFF6FD3FF)
    AgentRole.DEVICE -> Color(0xFF7BE0A4)
    AgentRole.VERIFIER -> Color(0xFFFFE07A)
    AgentRole.EXECUTOR -> Color(0xFFFF8A8A)
    AgentRole.WRITER -> Color(0xFFB0C4DE)
}

fun riskColor(r: Risk): Color = when (r) {
    Risk.NONE -> Color(0xFF7BE0A4); Risk.LOW -> Color(0xFF9BD7FF); Risk.MEDIUM -> Color(0xFFFFC970)
    Risk.HIGH -> Color(0xFFFF9466); Risk.CRITICAL -> Color(0xFFFF5D6C)
}
