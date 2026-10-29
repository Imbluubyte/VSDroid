package com.vsdroid.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Handles a mouse right-click (secondary button) by calling [onSecondaryClick] with the pointer position,
 * and swallows the rest of that gesture so the default text/click handlers never see it.
 * Right-clicking used to crash the app; it now does the same as a long press wherever this is used.
 * Touch input and the primary mouse button are not affected.
 */
fun Modifier.onSecondaryClick(onSecondaryClick: (Offset) -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onSecondaryClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            var swallowing = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (!swallowing && event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    swallowing = true
                    change.consume()
                    try {
                        latest(change.position)
                    } catch (e: Exception) {
                        // Never let a right-click handler take the app down.
                    }
                } else if (swallowing) {
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) swallowing = false
                }
            }
        }
    }
}
