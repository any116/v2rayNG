package com.v2ray.ang.ui.shortcut

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.ExternalEntry
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseEventEffect
import com.v2ray.ang.ui.base.BaseMessage
import com.v2ray.ang.ui.base.asString
import com.v2ray.ang.util.LogUtil

/**
 * Host surface of the headless service shortcut entries.
 */
@Composable
fun ShortcutScreen(
    viewModel: ShortcutViewModel,
    onEvent: (BaseEvent) -> Boolean
) {
    val context = LocalContext.current

    BaseEventEffect(events = viewModel.events, onEvent = { event ->
        when {
            onEvent(event) -> true

            event is BaseEvent.Message -> {
                context.showAsToast(event.message)
                true
            }

            event is BaseEvent.Navigate -> {
                val entry = ExternalEntry.fromRoute(event.route)
                if (entry == null) {
                    LogUtil.e(AppConfig.TAG, "No external entry for ${event.route}; ignoring request")
                } else {
                    context.startActivity(entry.toIntent(context))
                }
                true
            }

            else -> false
        }
    })
}

/**
 * Resolves a [BaseMessage] against resources and shows it as a Toast that outlives the screen.
 */
private fun Context.showAsToast(message: BaseMessage) {
    val duration = if (message.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    Toast.makeText(applicationContext, message.text.asString(this), duration).show()
}
