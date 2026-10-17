package com.v2ray.ang.ui.base

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Parent of every editor screen (Server*, SubEdit, RoutingEdit, UserAssetUrl…).
 *
 * Subclasses submit a save operation capturing its immutable command snapshot, or implement [doDelete]:
 * `null` means validation failed and the screen stays open (the subclass reports which field via
 * [toastError]); a [BaseResult] means success and the framework closes the screen with it.
 *
 * The success toast is shown by the caller from [BaseResult.notify], because the child's snackbar
 * host is already gone once it finishes.
 */
abstract class BaseEditViewModel<S : BaseUiState, A : BaseAction>(
    initialState: S,
) : BaseViewModel<S, A>(initialState) {

    private var mutationJob: Job? = null
    private val _isMutating = MutableStateFlow(false)

    /** True while a save or delete command is being validated or written. */
    val isMutating: StateFlow<Boolean> = _isMutating.asStateFlow()

    /** Delete the edited entity. Returns `null` by default (screen has no delete action). */
    protected open suspend fun doDelete(): BaseResult? = null

    /** Capture the submitted snapshot in [block], never in a mutable pending-form property. */
    protected fun save(block: suspend () -> BaseResult?) = mutate(block)
    protected fun delete() = mutate { doDelete() }

    private fun mutate(block: suspend () -> BaseResult?) {
        if (mutationJob?.isActive == true) return
        _isMutating.value = true
        mutationJob = launch {
            try {
                block()?.let(::finishWith)
            } finally {
                _isMutating.value = false
                mutationJob = null
            }
        }
    }

    protected fun cancel() = finishWith(BaseResult.Cancelled)

    override fun onCleared() {
        mutationJob?.cancel()
        mutationJob = null
        _isMutating.value = false
        super.onCleared()
    }
}
