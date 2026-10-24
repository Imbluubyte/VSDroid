package com.vsdroid.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** VS Code "Dark+" palette: dark grey, never pure black. */
object Vs {
    val editorBg = Color(0xFF1E1E1E)
    val sideBarBg = Color(0xFF252526)
    val activityBarBg = Color(0xFF333333)
    val titleBarBg = Color(0xFF3C3C3C)
    val tabBarBg = Color(0xFF252526)
    val tabInactiveBg = Color(0xFF2D2D2D)
    val panelBg = Color(0xFF1E1E1E)
    val statusBarBg = Color(0xFF007ACC)
    val border = Color(0xFF3C3C3C)
    val selection = Color(0xFF264F78)
    val hover = Color(0xFF2A2D2E)
    val activeRow = Color(0xFF37373D)
    val text = Color(0xFFCCCCCC)
    val textDim = Color(0xFF858585)
    val textBright = Color(0xFFFFFFFF)
    val lineNumber = Color(0xFF858585)
    val lineNumberActive = Color(0xFFC6C6C6)
    val accent = Color(0xFF007ACC)
    val error = Color(0xFFF48771)
    val warning = Color(0xFFCCA700)
    val inputBg = Color(0xFF3C3C3C)
    val popupBg = Color(0xFF252526)
    val scrim = Color(0x66000000)
}

val CodeFont = FontFamily.Monospace

fun codeStyle(sizeSp: Float) = TextStyle(
    fontFamily = CodeFont,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.45f).sp,
    color = Vs.text,
)

@Composable
fun VSDroidTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Vs.accent,
        onPrimary = Color.White,
        background = Vs.editorBg,
        onBackground = Vs.text,
        surface = Vs.sideBarBg,
        onSurface = Vs.text,
        surfaceVariant = Vs.inputBg,
        onSurfaceVariant = Vs.text,
        outline = Vs.border,
        error = Vs.error,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
