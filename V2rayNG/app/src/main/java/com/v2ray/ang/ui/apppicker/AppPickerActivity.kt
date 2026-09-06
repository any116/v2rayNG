package com.v2ray.ang.ui.apppicker

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppPickerActivity : BaseActivity() {

    private val viewModel: AppPickerViewModel by viewModels()

    @Composable
    override fun ScreenContent() = AppPickerScreen(viewModel)
}
