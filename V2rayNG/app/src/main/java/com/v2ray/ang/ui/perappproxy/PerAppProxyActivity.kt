package com.v2ray.ang.ui.perappproxy

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PerAppProxyActivity : BaseActivity() {

    private val viewModel: PerAppProxyViewModel by viewModels()

    @Composable
    override fun ScreenContent() = PerAppProxyScreen(viewModel)
}
