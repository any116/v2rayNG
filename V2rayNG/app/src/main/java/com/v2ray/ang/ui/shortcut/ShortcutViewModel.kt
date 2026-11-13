package com.v2ray.ang.ui.shortcut

import com.v2ray.ang.data.repository.ShortcutRepository
import com.v2ray.ang.ui.base.BaseViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel(assistedFactory = ShortcutViewModel.Factory::class)
class ShortcutViewModel @AssistedInject constructor(
    @Assisted command: ShortcutCommand,
    private val repo: ShortcutRepository
) : BaseViewModel<ShortcutUiState, ShortcutAction>(ShortcutUiState(command)) {

    @AssistedFactory
    interface Factory {
        fun create(command: ShortcutCommand): ShortcutViewModel
    }

    init {
        dispatchCommand()
    }

    override fun onAction(action: ShortcutAction) = Unit

    private fun dispatchCommand() = launch(onError = { reportFailureAndClose() }) {
        val running = repo.isCoreRunning()
        when (state.command) {
            // Idempotent on purpose: tapping "start" while running must not restart the core.
            ShortcutCommand.START -> if (!running) platform(ShortcutEvent.StartService)

            ShortcutCommand.STOP -> if (running) platform(ShortcutEvent.StopService)

            ShortcutCommand.SWITCH -> platform(
                if (running) ShortcutEvent.StopService else ShortcutEvent.StartService
            )
        }

        // All remaining commands run headless and must not leave a visible task behind.
        platform(ShortcutEvent.MoveToBack)
        finishWith()
    }

    private fun reportFailureAndClose() {
        toastError()
        finishWith()
    }
}
