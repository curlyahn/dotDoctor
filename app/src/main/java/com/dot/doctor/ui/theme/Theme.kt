package com.dot.doctor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Dot 앱과 같은 미니멀 다크 + 라임. 글씨는 Dot보다 한 단계씩 크게. */
object Dot {
    val Ink = Color(0xFF0B0B0C)
    val Surface = Color(0xFF151517)
    val Surface2 = Color(0xFF1E1E21)
    val Line = Color(0xFF26262A)
    val Text = Color(0xFFF4F4F2)
    val Muted = Color(0xFFA3A3A8)        // 큰 글씨에서도 잘 읽히게 Dot보다 밝게
    val Faint = Color(0xFF6A6A70)
    val Lime = Color(0xFFD4FF3A)         // 정상, 포인트
    val OnLime = Color(0xFF0B0B0C)
    val Amber = Color(0xFFFFC14D)        // 주의
    val Coral = Color(0xFFFF6B5B)        // 벗어남, 경고
}

private val scheme = darkColorScheme(
    primary = Dot.Lime, onPrimary = Dot.OnLime,
    secondary = Dot.Lime, onSecondary = Dot.OnLime,
    background = Dot.Ink, onBackground = Dot.Text,
    surface = Dot.Surface, onSurface = Dot.Text,
    surfaceVariant = Dot.Surface2, onSurfaceVariant = Dot.Muted,
    surfaceContainer = Dot.Surface, surfaceContainerHigh = Dot.Surface2,
    surfaceContainerHighest = Dot.Surface2, surfaceContainerLow = Dot.Surface,
    outline = Dot.Line, outlineVariant = Dot.Line,
    error = Dot.Coral,
)

private val typography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.03).em),
    headlineSmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.04.em),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun DotDoctorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
