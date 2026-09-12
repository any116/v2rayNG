package com.v2ray.ang.ui.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.v2ray.ang.R
import com.v2ray.ang.repository.TranslatorGroup
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseUiState

enum class AboutPage(@StringRes val titleRes: Int) {
    MENU(R.string.title_about),
    TRANSLATORS(R.string.title_translators),
    OSS_LICENSE(R.string.title_oss_license)
}

@Immutable
data class AboutUiState(
    val versionText: String = "",
    val appId: String = "",
    val page: AboutPage = AboutPage.MENU,
    val translators: List<TranslatorGroup> = emptyList()
) : BaseUiState

enum class AboutEntry(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int
) {
    SOURCE_CODE(R.drawable.ic_source_code_24dp, R.string.title_source_code),
    OSS_LICENSE(R.drawable.license_24px, R.string.title_oss_license),
    TRANSLATORS(R.drawable.ic_translate_24dp, R.string.title_translators),
    FEEDBACK(R.drawable.ic_feedback_24dp, R.string.title_pref_feedback),
    TG_CHANNEL(R.drawable.ic_telegram_24dp, R.string.title_tg_channel),
    PRIVACY_POLICY(R.drawable.ic_privacy_24dp, R.string.title_privacy_policy)
}

sealed interface AboutAction : BaseAction {

    data object Back : AboutAction

    data class EntryClicked(val entry: AboutEntry) : AboutAction

    data class LinkClicked(val url: String) : AboutAction
}
