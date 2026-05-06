package com.namhyun.reflect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 다크 톤 통일 — 라이트 모드도 같은 다크 팔레트 사용 (의도된 디자인)
private val Scheme = darkColorScheme(
    background = Color(0xFF0A0E1A),
    onBackground = Color(0xFFEDF1FA),
    surface = Color(0xFF141828),
    onSurface = Color(0xFFEDF1FA),
    surfaceVariant = Color(0xFF1C2236),
    onSurfaceVariant = Color(0xFFB7BFD2),
    primary = Color(0xFF7BA8FF),
    onPrimary = Color(0xFF0A0E1A),
    primaryContainer = Color(0xFF1F2D52),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFB7BFD2),
    onSecondary = Color(0xFF141828),
    error = Color(0xFFFF6B7B),
    outline = Color(0x1FFFFFFF),
    outlineVariant = Color(0x14FFFFFF),
)

private val ReflectTypography = Typography(
    displayLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF7C849A)),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ReflectTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = Scheme,
        typography = ReflectTypography,
        content = content,
    )
}
