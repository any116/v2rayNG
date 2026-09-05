package com.v2ray.ang.ui.about

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AboutActivity : BaseActivity() {

    private val viewModel: AboutViewModel by viewModels()

    @Composable
    override fun ScreenContent() = AboutScreen(viewModel)
}
