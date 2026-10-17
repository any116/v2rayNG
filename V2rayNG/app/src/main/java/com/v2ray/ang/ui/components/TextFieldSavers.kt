package com.v2ray.ang.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope

/**
 * Uses the foundation saver for one state-based text field, including its selection.
 *
 * Keeping this boundary in one place makes feature savers consistent and lets restore paths reject
 * malformed nested payloads instead of throwing from [TextFieldState.Saver].
 */
internal fun SaverScope.saveTextField(state: TextFieldState): Any =
    checkNotNull(with(TextFieldState.Saver) { save(state) })

internal fun restoreTextField(saved: Any?): TextFieldState? =
    saved?.let { runCatching { TextFieldState.Saver.restore(it) }.getOrNull() }

/** A strict list saver: a truncated or malformed entry invalidates the whole list. */
internal val TextFieldStateListSaver: Saver<List<TextFieldState>, Any> = Saver(
    save = { states -> states.map { saveTextField(it) } },
    restore = { saved ->
        (saved as? List<*>)?.let { values ->
            values.mapNotNull(::restoreTextField).takeIf { restored -> restored.size == values.size }
        }
    }
)
