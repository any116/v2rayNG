package com.v2ray.ang.ui.subscription

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SubEditActivity : BaseActivity() {

    private val viewModel: SubEditViewModel by viewModels()

    @Composable
    override fun ScreenContent() = SubEditScreen(viewModel)
}
