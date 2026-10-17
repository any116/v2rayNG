package com.v2ray.ang.ui.base

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle

/**
 * Read-only compatibility for String form snapshots written before UI-owned text drafts.
 *
 * Only the old user-edited payload is a valid initial seed. New drafts are saved by the UI's
 * TextFieldState/feature Saver, so there is deliberately no provider or mutable String writer.
 * Remove this bridge in app version 2.4.0, after one release has passed with the state-based
 * editor savers in production.
 */
internal class EditFormSaver(
    private val handle: SavedStateHandle,
    private val key: String
) {

    /**
     * Consumes the restored snapshot, or returns null when nothing user-edited was saved.
     *
     * This is intentionally a destructive read: the old String snapshot is only a migration seed
     * and must not remain in SavedStateHandle to compete with the UI-owned saver on a later save.
     */
    fun consume(): Bundle? = handle.remove<Bundle>(key)?.takeIf { it.getBoolean(KEY_DIRTY, false) }

    private companion object {
        const val KEY_DIRTY = "user_edited"
    }
}
