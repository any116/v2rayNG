package com.v2ray.ang.ui.routing

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RoutingEditActivity : BaseActivity() {

    private val viewModel: RoutingEditViewModel by viewModels()

    @Composable
    override fun ScreenContent() = RoutingEditScreen(viewModel)
}
