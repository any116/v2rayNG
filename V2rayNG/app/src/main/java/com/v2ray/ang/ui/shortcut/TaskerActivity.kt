package com.v2ray.ang.ui.shortcut

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.base.BaseActivity
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.asString
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TaskerActivity : BaseActivity() {

    private val viewModel: TaskerViewModel by viewModels()

    @Composable
    override fun ScreenContent() = TaskerScreen(viewModel, ::handleEvent)

    /**
     * Translates the save event into the Tasker/Locale protocol.
     *
     * @return true when the event has been consumed.
     */
    private fun handleEvent(event: BaseEvent): Boolean = when (event) {
        is TaskerEvent.SaveSetting -> {
            val extras = Bundle().apply {
                putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, event.startService)
                putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, event.guid)
            }
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(AppConfig.TASKER_EXTRA_BUNDLE, extras)
                    .putExtra(AppConfig.TASKER_EXTRA_STRING_BLURB, event.blurb.asString(this))
            )
            finish()
            true
        }

        else -> false
    }
}
