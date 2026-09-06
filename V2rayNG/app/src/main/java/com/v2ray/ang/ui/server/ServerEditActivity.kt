package com.v2ray.ang.ui.server

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ServerEditActivity : BaseActivity() {

    private val viewModel: ServerEditViewModel by viewModels()

    @Composable
    override fun ScreenContent() = ServerEditScreen(viewModel)
}
