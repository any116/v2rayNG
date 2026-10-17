package com.v2ray.ang.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect

/**
 * Owns the generic search field lifecycle for a screen.
 *
 * The input and active flag are remembered with the root composition. The controller only knows
 * how to open/close that generic field and observe it; screen actions remain the caller's concern.
 */
@Stable
class SearchFieldController internal constructor(
    val input: TextFieldState,
    private val activeState: MutableState<Boolean>
) {

    var isActive: Boolean
        get() = activeState.value
        private set(value) {
            activeState.value = value
        }

    /** Opens the field before dispatching the screen-specific action. */
    fun open(onOpen: () -> Unit) {
        isActive = true
        onOpen()
    }

    /** Clears the field, closes it, and only then dispatches the screen-specific action. */
    fun close(onClose: () -> Unit) {
        closeSearchField(input) {
            isActive = false
            onClose()
        }
    }
}

/**
 * Remembers a saveable search input and its active flag. The supplied values are only initial
 * seeds; restored/user-edited state is never replaced by later ViewModel emissions.
 */
@Composable
fun rememberSearchFieldController(
    initialQuery: String,
    initialActive: Boolean
): SearchFieldController {
    val input = rememberSaveable(saver = TextFieldState.Saver) {
        TextFieldState(initialQuery)
    }
    val activeState = rememberSaveable { mutableStateOf(initialActive) }
    return remember(input, activeState) { SearchFieldController(input, activeState) }
}

/** Collects one-way query snapshots while the shared field is active. */
@Composable
fun SearchFieldController.ObserveQuery(onQuery: (String) -> Unit) {
    val latestOnQuery = rememberUpdatedState(onQuery)
    LaunchedEffect(input) {
        observeSearchQuery(input, { isActive }) { query -> latestOnQuery.value(query) }
    }
}

/**
 * Observes only the live text while the field is active. A query is checked again before dispatch
 * so a close that clears the field cannot submit a stale snapshot to a screen reducer.
 */
internal suspend fun observeSearchQuery(
    input: TextFieldState,
    isActive: () -> Boolean,
    onQuery: (String) -> Unit
) {
    snapshotFlow { if (isActive()) input.text.toString() else null }.collect { query ->
        if (query != null && isActive() && query == input.text.toString()) onQuery(query)
    }
}

/** Clears synchronously before the caller dispatches its search-close action. */
internal fun closeSearchField(input: TextFieldState, onClose: () -> Unit) {
    input.clearText()
    onClose()
}
