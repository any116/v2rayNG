package com.v2ray.ang.ui.subscription

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SubSettingActivity : BaseActivity() {

    private val viewModel: SubSettingViewModel by viewModels()

    @Composable
    override fun ScreenContent() = SubSettingScreen(viewModel)
}
