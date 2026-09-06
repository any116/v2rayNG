package com.v2ray.ang.ui.userasset

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseHelperActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class UserAssetActivity : BaseHelperActivity() {

    private val viewModel: UserAssetViewModel by viewModels()

    @Composable
    override fun ScreenContent() = UserAssetScreen(viewModel)
}
