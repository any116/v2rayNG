package com.v2ray.ang.ui.shortcut

import androidx.compose.runtime.Immutable
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseUiState

/**
 * The job a service shortcut performs. The three service entries share a ViewModel;
 * the scanner shortcut delegates directly to Main's navigation graph.
 */
enum class ShortcutCommand {
    START, STOP, SWITCH
}

@Immutable
data class ShortcutUiState(
    /**
     * Which entry is running.
     *
     * The default exists only to satisfy the "every field has a default" rule. Hosts MUST pass the
     * real command: falling back to [ShortcutCommand.SWITCH] would silently turn a "stop" shortcut
     * into a toggle.
     */
    val command: ShortcutCommand = ShortcutCommand.SWITCH
) : BaseUiState

/** Headless service entries have no user actions; the command is supplied at creation. */
sealed interface ShortcutAction : BaseAction

/**
 * Platform-level events.
 */
sealed interface ShortcutEvent : BaseEvent.Platform {
    data object MoveToBack : ShortcutEvent
    data object StartService : ShortcutEvent
    data object StopService : ShortcutEvent
}
