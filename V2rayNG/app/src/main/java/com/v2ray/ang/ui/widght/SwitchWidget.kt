package com.v2ray.ang.ui.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.WidgetRunState
import com.v2ray.ang.handler.WidgetStateManager
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.ui.components.colorFabActive

private val ActiveColor = colorFabActive
private val InactiveColor = Color(0xFF9C9C9C)
private val AttentionColor = Color(0xFFBA1A1A)
private val WidgetContentColor = Color.White
private val IconSize = 32.dp
private val WidgetCornerRadius = 24.dp
private val WidgetPadding = 8.dp
private val LabelGap = 2.dp
private val LabelSize = 12.sp

/**
 * Declarative switch widget. Glance turns this into RemoteViews on every supported API level,
 * so there is no separate legacy layout path to keep in sync.
 */
class SwitchWidget : GlanceAppWidget() {

    /** Re-render on resize instead of stretching a single fixed layout. */
    override val sizeMode: SizeMode = SizeMode.Exact

    /** The switch is owned by the core, so there is no widget-local state to persist. */
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Runs when a session starts: the snapshot may have been written by a process that died.
        WidgetStateManager.reconcile(context)
        provideContent { SwitchContent() }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { SwitchContent(preview = WidgetRunState.STOPPED) }
    }
}

@Composable
private fun SwitchContent(preview: WidgetRunState? = null) {
    val context = LocalContext.current
    val live by WidgetStateManager.state.collectAsState()
    val state = preview ?: live
    val description = context.getString(if (state.isActive) R.string.acc_stop else R.string.acc_start)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .widgetBackground(state)
            .clickable(actionSendBroadcast(clickIntent(context)))
            .padding(WidgetPadding),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        if (state.isPending) {
            CircularProgressIndicator(
                modifier = GlanceModifier.size(IconSize),
                color = ColorProvider(WidgetContentColor),
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_power_settings_new_24dp),
                contentDescription = description,
                modifier = GlanceModifier.size(IconSize),
            )
        }
        Spacer(GlanceModifier.height(LabelGap))
        Text(
            text = context.getString(R.string.app_name),
            style = TextStyle(color = ColorProvider(WidgetContentColor), fontSize = LabelSize),
        )
    }
}

/**
 * One action for every instance on purpose: the switch has no per-instance configuration, so all
 * instances may share a single PendingIntent. Add the appWidgetId here if that ever changes.
 */
private fun clickIntent(context: Context): Intent =
    Intent(context, WidgetProvider::class.java).setAction(AppConfig.BROADCAST_ACTION_WIDGET_CLICK)

/**
 * Rounded corners are a platform modifier from API 31. Older launchers still receive the same
 * Compose color and fall back to the platform's default widget shape.
 */
private fun GlanceModifier.widgetBackground(state: WidgetRunState): GlanceModifier {
    val attention = state == WidgetRunState.FAILED || state == WidgetRunState.PERMISSION_REQUIRED
    val color = when {
        attention -> AttentionColor
        state.isActive || state == WidgetRunState.STARTING -> ActiveColor
        else -> InactiveColor
    }

    return background(ColorProvider(color)).run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cornerRadius(WidgetCornerRadius)
        } else {
            this
        }
    }
}
