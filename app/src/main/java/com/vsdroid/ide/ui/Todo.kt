package com.vsdroid.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.IdeState

/**
 * The To-Do dropdown: a panel under the top bar with the open project's tasks. It is drawn inside the main
 * window (not as a popup window) because it has a text field, and popups that don't take focus can't type.
 * Tapping outside it, or Esc, closes it.
 */
@Composable
fun TodoDropdown(ide: IdeState) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
            // The field wasn't ready yet; the user can tap it.
        }
    }
    fun add() {
        if (ide.addTodo(text)) text = ""
    }

    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            ide.todoOpen = false
        },
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 8.dp)
                .width(340.dp)
                .heightIn(max = 440.dp)
                .background(Vs.popupBg, shape)
                .border(1.dp, Vs.border, shape)
                // Swallow taps inside the panel so they don't reach the backdrop and close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("To-Do", color = Vs.textBright, fontSize = 15.sp)
                    Text(
                        ide.root?.name ?: "", color = Vs.textDim, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ide.todos.any { it.done }) {
                    Text(
                        "Clear done", color = Vs.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { ide.clearDoneTodos() }.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Vs.textBright, fontSize = 14.sp),
                    cursorBrush = SolidColor(Vs.textBright),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) Text("Add a task", color = Vs.textDim, fontSize = 14.sp)
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .background(Vs.inputBg, RoundedCornerShape(2.dp))
                        .border(1.dp, Vs.accent, RoundedCornerShape(2.dp))
                        .padding(10.dp)
                        .focusRequester(focus),
                )
                Spacer(Modifier.width(8.dp))
                FlatButton("Add", { add() })
            }
            Spacer(Modifier.height(8.dp))

            if (ide.todos.isEmpty()) {
                Text("Nothing to do yet. Add a task above.", color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ide.todos.forEachIndexed { i, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(34.dp).clickable { ide.toggleTodo(i) }, contentAlignment = Alignment.Center) {
                                Icon(
                                    if (item.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                    if (item.done) "Mark as not done" else "Mark as done",
                                    tint = if (item.done) Vs.accent else Vs.text, modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                item.text,
                                color = if (item.done) Vs.textDim else Vs.text,
                                fontSize = 14.sp,
                                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f).clickable { ide.toggleTodo(i) }.padding(vertical = 8.dp),
                            )
                            Box(Modifier.size(34.dp).clickable { ide.deleteTodo(i) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, "Delete task", tint = Vs.textDim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
