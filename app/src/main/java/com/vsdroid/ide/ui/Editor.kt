package com.vsdroid.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.EditorTab
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.core.Completion
import com.vsdroid.ide.core.EditorOps
import com.vsdroid.ide.core.Syntax
import com.vsdroid.ide.core.ThemeManager
import kotlin.math.roundToInt

private class SyntaxTransformation(private val tab: EditorTab) : VisualTransformation {
    private var lastText: String? = null
    private var lastResult: AnnotatedString = AnnotatedString("")

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        if (s != lastText) {
            // Never let a highlighting bug crash typing: fall back to plain text.
            lastResult = try {
                Syntax.highlight(s, tab.language)
            } catch (e: Exception) {
                AnnotatedString(s)
            }
            lastText = s
        }
        return TransformedText(lastResult, OffsetMapping.Identity)
    }
}

@Composable
fun CodeEditor(tab: EditorTab, ide: IdeState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val style = remember(ide.fontSize, ThemeManager.current) { codeStyle(ide.fontSize) }
    val lineHeightPx = with(density) { (ide.fontSize * 1.45f).sp.toPx() }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val transformation = remember(tab, ThemeManager.current) { SyntaxTransformation(tab) }

    var layout by remember(tab) { mutableStateOf<TextLayoutResult?>(null) }
    var selectedSuggestion by remember(tab) { mutableIntStateOf(0) }
    var dismissedAt by remember(tab) { mutableIntStateOf(-1) }

    val text = tab.value.text
    val cursor = tab.value.selection.end
    val suggestions = remember(text, cursor, dismissedAt) {
        if (tab.value.selection.collapsed && dismissedAt != cursor) {
            Completion.suggest(text, cursor, tab.language)
        } else {
            emptyList()
        }
    }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val currentLine = remember(text, cursor) { EditorOps.lineCol(text, cursor).first }

    LaunchedEffect(tab.scrollRequest) {
        if (tab.scrollRequest > 0 && tab.scrollToLine > 0) {
            val y = ((tab.scrollToLine - 4).coerceAtLeast(0) * lineHeightPx).roundToInt()
            vScroll.animateScrollTo(y)
        }
    }
    LaunchedEffect(tab) {
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
            // Field not attached yet; the user can tap it.
        }
    }

    fun accept(word: String) {
        val v = tab.value
        val (start, _) = Completion.wordBefore(v.text, v.selection.end)
        val out = v.text.substring(0, start) + word + v.text.substring(v.selection.end)
        tab.value = TextFieldValue(out, TextRange(start + word.length))
        selectedSuggestion = 0
    }

    BoxWithConstraints(modifier.background(Vs.editorBg)) {
        val minHeight = maxHeight
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            // Line-number gutter
            val gutterText = remember(lineCount) { (1..lineCount).joinToString("\n") }
            Box(Modifier.heightIn(min = minHeight).padding(start = 8.dp, end = 12.dp, top = 4.dp)) {
                Text(
                    gutterText,
                    style = style.copy(color = Vs.lineNumber, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    modifier = Modifier.width((lineCount.toString().length.coerceAtLeast(2) * ide.fontSize * 0.62f + 4).dp),
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
                    .drawBehind {
                        // The layout lags one frame behind the text; only use it once it matches.
                        val l = layout?.takeIf { it.layoutInput.text.text == text }
                        if (l != null && tab.value.selection.collapsed && cursor in 0..text.length) {
                            val line = l.getLineForOffset(cursor)
                            val top = l.getLineTop(line) + 4.dp.toPx()
                            drawRect(
                                color = Vs.lineHighlight,
                                topLeft = Offset(0f, top),
                                size = Size(size.width, l.getLineBottom(line) - l.getLineTop(line)),
                            )
                        }
                    },
            ) {
                BasicTextField(
                    value = tab.value,
                    onValueChange = { new ->
                        val old = tab.value
                        tab.value = try {
                            EditorOps.afterEdit(old, new, tab.language, ide.settings.autoClosePairs)
                        } catch (e: Exception) {
                            new
                        }
                        if (new.text != old.text) selectedSuggestion = 0
                        if (ide.autoSave && tab.dirty) ide.save(tab)
                    },
                    textStyle = style,
                    cursorBrush = SolidColor(Vs.textBright),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .heightIn(min = minHeight)
                        .padding(top = 4.dp, end = 120.dp, bottom = 240.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val shown = suggestions.isNotEmpty()
                            when {
                                e.key == Key.Tab && !e.isCtrlPressed -> {
                                    if (shown && !e.isShiftPressed) {
                                        accept(suggestions[selectedSuggestion.coerceIn(0, suggestions.lastIndex)])
                                    } else if (e.isShiftPressed) {
                                        tab.value = EditorOps.outdent(tab.value)
                                    } else {
                                        tab.value = EditorOps.indent(tab.value)
                                    }
                                    true
                                }
                                shown && e.key == Key.DirectionDown && !e.isAltPressed -> {
                                    selectedSuggestion = (selectedSuggestion + 1) % suggestions.size
                                    true
                                }
                                shown && e.key == Key.DirectionUp && !e.isAltPressed -> {
                                    selectedSuggestion = (selectedSuggestion - 1 + suggestions.size) % suggestions.size
                                    true
                                }
                                shown && e.key == Key.Escape -> {
                                    dismissedAt = cursor
                                    true
                                }
                                else -> false
                            }
                        },
                )

                // Autocomplete popup, anchored under the caret and scrolling with the text.
                // Crash fix: the cursor moves before the text layout is recomputed, so asking a stale layout
                // for the cursor rectangle threw "offset out of bounds". Skip the popup until they match.
                val l = layout?.takeIf { it.layoutInput.text.text == text }
                if (suggestions.isNotEmpty() && l != null && cursor in 0..text.length) {
                    val rect = l.getCursorRect(cursor)
                    val topPad = with(density) { 4.dp.toPx() }
                    Column(
                        Modifier
                            .offset { IntOffset(rect.left.roundToInt(), (rect.bottom + topPad).roundToInt()) }
                            .width(260.dp)
                            .border(1.dp, Vs.border)
                            .background(Vs.popupBg),
                    ) {
                        suggestions.forEachIndexed { i, word ->
                            val selected = i == selectedSuggestion.coerceIn(0, suggestions.lastIndex)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) Vs.selection else Vs.popupBg)
                                    .clickable { accept(word) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    word,
                                    color = if (selected) Vs.textBright else Vs.text,
                                    fontFamily = CodeFont,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
