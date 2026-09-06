package com.v2ray.ang.ui.routing

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseHelperActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RoutingSettingActivity : BaseHelperActivity() {

    private val viewModel: RoutingSettingViewModel by viewModels()

    private val platformEventHandler: (RoutingEvent) -> Boolean = { event ->
        when (event) {
            RoutingEvent.ScanQrCode -> {
                scanQrCode { text -> viewModel.onAction(RoutingAction.QrCodeScanned(text)) }
                true
            }
            else -> false
        }
    }

    @Composable
    override fun ScreenContent() = RoutingSettingScreen(viewModel, platformEventHandler)
}
