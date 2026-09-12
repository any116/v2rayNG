package com.v2ray.ang.ui.about

import com.v2ray.ang.AppConfig
import com.v2ray.ang.repository.AboutRepository
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val repo: AboutRepository
) : BaseViewModel<AboutUiState, AboutAction>(AboutUiState()) {

    private var loadJob: Job? = null

    init {
        setState { copy(versionText = repo.shortVersionText(), appId = repo.applicationId()) }

        loadJob = launch(onError = { LogUtil.e(AppConfig.TAG, "About: background load failed", it) }) {
            val versionText = repo.fullVersionText()
            setState { copy(versionText = versionText) }
            val translators = repo.loadTranslators()
            setState { copy(translators = translators) }
        }
    }

    override fun onAction(action: AboutAction) {
        when (action) {
            AboutAction.Back -> back()
            is AboutAction.EntryClicked -> openEntry(action.entry)
            is AboutAction.LinkClicked -> openUrl(action.url)
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        super.onCleared()
    }

    private fun back() {
        if (state.page != AboutPage.MENU) {
            setState { copy(page = AboutPage.MENU) }
        } else {
            finishWith(BaseResult.Cancelled)
        }
    }

    private fun openEntry(entry: AboutEntry) {
        when (entry) {
            AboutEntry.SOURCE_CODE -> openUrl(repo.sourceCodeUrl())
            AboutEntry.OSS_LICENSE -> setState { copy(page = AboutPage.OSS_LICENSE) }
            AboutEntry.TRANSLATORS -> setState { copy(page = AboutPage.TRANSLATORS) }
            AboutEntry.FEEDBACK -> openUrl(repo.issuesUrl())
            AboutEntry.TG_CHANNEL -> openUrl(repo.tgChannelUrl())
            AboutEntry.PRIVACY_POLICY -> openUrl(repo.privacyPolicyUrl())
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) {
            LogUtil.w(AppConfig.TAG, "About: blank URL requested")
            toastError()
        } else {
            navigate(AppRoute.OpenUrl(url))
        }
    }
}
