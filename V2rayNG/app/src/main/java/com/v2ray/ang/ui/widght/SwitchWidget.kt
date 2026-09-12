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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
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
import com.v2ray.ang.ui.compose.colorFabActive

private val ButtonSize = 48.dp
private val IconSize = 24.dp
private val ButtonRadius = 24.dp
private val WidgetPadding = 4.dp
private val LabelSize = 12.sp

// Only the semantic colors are shared with the app; AppTheme itself cannot cross into Glance.
private val ActiveColor = colorFabActive
private val InactiveColor = Color(0xFF9C9C9C)
private val AttentionColor = Color(0xFFD50000)
private val OnButtonColor = Color.White

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
            .padding(WidgetPadding)
            .clickable(actionSendBroadcast(clickIntent(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Box(
            modifier = GlanceModifier.size(ButtonSize).switchBackground(state),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isPending) {
                CircularProgressIndicator(
                    modifier = GlanceModifier.size(IconSize),
                    color = ColorProvider(OnButtonColor),
                )
            } else {
                Image(
                    provider = ImageProvider(
                        if (state.isActive) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
                    ),
                    contentDescription = description,
                    modifier = GlanceModifier.size(IconSize),
                )
            }
        }
        Text(
            text = context.getString(R.string.app_name),
            style = TextStyle(color = ColorProvider(OnButtonColor), fontSize = LabelSize),
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
 * Rounded corners are a platform modifier from API 31; below that the existing shape drawables
 * keep the round look instead of degrading to a square button.
 */
private fun GlanceModifier.switchBackground(state: WidgetRunState): GlanceModifier {
    val attention = state == WidgetRunState.FAILED || state == WidgetRunState.PERMISSION_REQUIRED
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val color = when {
            attention -> AttentionColor
            state.isActive || state == WidgetRunState.STARTING -> ActiveColor
            else -> InactiveColor
        }
        background(ColorProvider(color)).cornerRadius(ButtonRadius)
    } else {
        background(
            ImageProvider(
                if (state.isActive || state == WidgetRunState.STARTING) {
                    R.drawable.ic_rounded_corner_active
                } else {
                    R.drawable.ic_rounded_corner_inactive
                }
            )
        )
    }
}
