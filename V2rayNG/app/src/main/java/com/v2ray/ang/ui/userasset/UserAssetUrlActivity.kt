package com.v2ray.ang.ui.userasset

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class UserAssetUrlActivity : BaseActivity() {

    private val viewModel: UserAssetUrlViewModel by viewModels()

    @Composable
    override fun ScreenContent() = UserAssetUrlScreen(viewModel)
}
