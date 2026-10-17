package com.v2ray.ang.ui.components

import androidx.annotation.MainThread
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Flushes state-based form fields before an external action reads UiState. */
@Stable
class TextFieldStateRegistry internal constructor() {
    private val flushers = LinkedHashMap<TextFieldState, () -> Unit>()

    internal fun register(state: TextFieldState, flush: () -> Unit) {
        flushers[state] = flush
    }

    internal fun unregister(state: TextFieldState) {
        flushers.remove(state)
    }

    /** Delivers the latest local text to each registered value-based UDF boundary. */
    @MainThread
    fun flush() {
        flushers.values.toList().forEach { it() }
    }
}

internal val LocalTextFieldStateRegistry =
    staticCompositionLocalOf<TextFieldStateRegistry?> { null }

@Composable
internal fun rememberTextFieldStateRegistry(): TextFieldStateRegistry =
    remember { TextFieldStateRegistry() }

/**
 * Bridges a state-based text field to the app's value-based UDF boundary.
 *
 * [TextFieldState] owns cursor and selection state locally, while [value] remains the external
 * source of truth. Programmatic synchronization is filtered so only user edits reach
 * [onValueChange].
 */
@Composable
internal fun rememberSynchronizedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
    registerForFlush: Boolean = true,
    onFlush: ((String) -> Unit)? = null,
    setExternalValue: (TextFieldState, String) -> Unit = { state, text ->
        state.setTextAndPlaceCursorAtEnd(text)
    },
): TextFieldState {
    val state = rememberTextFieldState(initialText = value)
    synchronizeTextFieldState(
        state = state,
        value = value,
        onValueChange = onValueChange,
        registerForFlush = registerForFlush,
        onFlush = onFlush,
        setExternalValue = setExternalValue,
    )
    return state
}

@Composable
internal fun synchronizeTextFieldState(
    state: TextFieldState,
    value: String,
    onValueChange: (String) -> Unit,
    registerForFlush: Boolean = true,
    onFlush: ((String) -> Unit)? = null,
    preferLocalState: Boolean = false,
    setExternalValue: (TextFieldState, String) -> Unit = { field, text ->
        field.setTextAndPlaceCursorAtEnd(text)
    },
) {
    val pendingEchoes = remember(state) { ArrayDeque<String>() }
    val initialText = remember(state) { state.text.toString() }
    var lastExternalValue by remember(state) { mutableStateOf(value) }
    var programmaticValue by remember(state) { mutableStateOf<String?>(null) }
    var hasProcessedExternalValue by remember(state) { mutableStateOf(false) }
    val registry = LocalTextFieldStateRegistry.current
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFlush by rememberUpdatedState(onFlush ?: onValueChange)
    val currentSetExternalValue by rememberUpdatedState(setExternalValue)
    val flushPendingValue = rememberUpdatedState {
        val text = state.text.toString()
        if (text != currentValue) {
            if (pendingEchoes.lastOrNull() != text) pendingEchoes.addLast(text)
            currentOnFlush(text)
        }
    }

    if (registerForFlush) {
        DisposableEffect(registry, state) {
            registry?.register(state) { flushPendingValue.value() }
            onDispose { registry?.unregister(state) }
        }
    }

    LaunchedEffect(value) {
        val current = state.text.toString()
        if (!hasProcessedExternalValue) {
            hasProcessedExternalValue = true
            if (preferLocalState && value != current) return@LaunchedEffect
        }
        val echoes = pendingEchoes.toList()
        val echoIndex = if (value == current) {
            echoes.indexOfLast { it == value }
        } else {
            echoes.indexOfFirst { it == value }
        }
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingEchoes.removeFirst() }
            lastExternalValue = value
            return@LaunchedEffect
        }
        if (value == current) {
            lastExternalValue = value
            return@LaunchedEffect
        }
        if (pendingEchoes.isEmpty() && current != lastExternalValue) {
            // A local edit may exist before snapshotFlow has delivered it. Let that collector
            // report the text instead of replacing it with an async load result.
            return@LaunchedEffect
        }
        pendingEchoes.clear()
        lastExternalValue = value
        programmaticValue = value
        currentSetExternalValue(state, value)
    }

    LaunchedEffect(state) {
        var firstEmission = true
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collect { text ->
                if (firstEmission) {
                    firstEmission = false
                    if (text == initialText) return@collect
                }
                if (text == programmaticValue) {
                    programmaticValue = null
                    return@collect
                }
                if (pendingEchoes.size > 64) pendingEchoes.removeFirst()
                if (pendingEchoes.lastOrNull() == text) return@collect
                pendingEchoes.addLast(text)
                currentOnValueChange(text)
            }
    }

}
