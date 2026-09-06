package com.v2ray.ang.ui.checkupdate

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CheckUpdateActivity : BaseActivity() {

    private val viewModel: CheckUpdateViewModel by viewModels()

    @Composable
    override fun ScreenContent() = CheckUpdateScreen(viewModel)
}
