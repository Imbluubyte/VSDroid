package com.vsdroid.ide.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.core.ThemeKey
import com.vsdroid.ide.core.ThemeManager

/**
 * The colours of the active theme (built-in Dark+/Light+ or a user theme file). Every property reads the
 * theme state, so composables that use them repaint when the theme changes. Don't hard-code colours elsewhere.
 */
object Vs {
    private operator fun get(k: ThemeKey): Color = ThemeManager.current[k]

    val isDark: Boolean get() = ThemeManager.current.dark

    val editorBg get() = this[ThemeKey.EditorBg]
    val sideBarBg get() = this[ThemeKey.SideBarBg]
    val activityBarBg get() = this[ThemeKey.ActivityBarBg]
    val activityBarFg get() = this[ThemeKey.ActivityBarFg]
    val activityBarFgActive get() = this[ThemeKey.ActivityBarFgActive]
    val titleBarBg get() = this[ThemeKey.TitleBarBg]
    val tabBarBg get() = this[ThemeKey.TabBarBg]
    val tabInactiveBg get() = this[ThemeKey.TabInactiveBg]
    val panelBg get() = this[ThemeKey.PanelBg]
    val border get() = this[ThemeKey.Border]
    val selection get() = this[ThemeKey.Selection]
    val hover get() = this[ThemeKey.Hover]
    val activeRow get() = this[ThemeKey.ActiveRow]
    val text get() = this[ThemeKey.Text]
    val textDim get() = this[ThemeKey.TextDim]
    val textBright get() = this[ThemeKey.TextBright]
    val lineNumber get() = this[ThemeKey.LineNumber]
    val lineNumberActive get() = this[ThemeKey.LineNumberActive]
    val lineHighlight get() = this[ThemeKey.LineHighlight]
    val accent get() = this[ThemeKey.Accent]
    val error get() = this[ThemeKey.Error]
    val warning get() = this[ThemeKey.Warning]
    val success get() = this[ThemeKey.Success]
    val modified get() = this[ThemeKey.Modified]
    val danger get() = this[ThemeKey.Danger]
    val inputBg get() = this[ThemeKey.InputBg]
    val popupBg get() = this[ThemeKey.PopupBg]
    val scrim get() = this[ThemeKey.Scrim]
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
    val t = ThemeManager.current
    val scheme = if (t.dark) {
        darkColorScheme(
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
    } else {
        lightColorScheme(
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
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
