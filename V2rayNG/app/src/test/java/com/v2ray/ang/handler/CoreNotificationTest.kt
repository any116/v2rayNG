package com.v2ray.ang.handler

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.withSettings
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.isA
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CoreNotificationTest {
    private val resources = mock<Resources> {
        on { getString(R.string.app_name) }.thenReturn("v2rayNG")
        on { getString(R.string.notification_action_stop_v2ray) }.thenReturn("Stop")
        on { getString(R.string.title_service_restart) }.thenReturn("Restart service")
    }
    private val context = mock<Context> {
        on { this.resources }.thenReturn(this@CoreNotificationTest.resources)
    }
    private val contentIntent = mock<PendingIntent>()
    private val stopIntent = mock<PendingIntent>()
    private val restartIntent = mock<PendingIntent>()

    @Test
    fun startupAndScreenOffKeepActionsWithoutAnEmptyBigTextStyle() {
        withBuilders { builders ->
            listOf(null, "", " \n\t").forEach { text ->
                render(CoreNotificationContent(text = text))
                val builder = builders.constructed().last()
                verify(builder).setContentTitle("v2rayNG")
                verify(builder).setContentText(null)
                verify(builder, never()).setStyle(anyOrNull())
                verifyActions(builder)
            }
        }
    }

    @Test
    fun speedUpdatesAndWakeRebuildsAlwaysCreateCompleteNotifications() {
        val speed = "proxy • 0 B/s↑ 0 B/s↓\ndirect • 0 B/s↑ 0 B/s↓"
        val running = CoreNotificationContent(title = "server")
        val states = listOf(
            running,
            running.copy(text = speed, icon = R.drawable.ic_stat_proxy),
            running, // screen off: no stale speed and no empty expanded style
            running, // wake/unlock: rebuild even before the next traffic sample
            running.copy(text = speed), // zero traffic still has the two actions
            CoreNotificationContent(title = "new server") // restart/profile switch
        )
        withBuilders { builders ->
            states.forEach(::render)
            assertEquals(states.size, builders.constructed().size)
            builders.constructed().zip(states).forEach { (builder, state) ->
                verify(builder).setContentTitle(state.title)
                verify(builder).setSmallIcon(state.icon)
                verify(builder).setContentText(state.text)
                if (state.text == null) {
                    verify(builder, never()).setStyle(anyOrNull())
                } else {
                    verify(builder).setStyle(isA<NotificationCompat.BigTextStyle>())
                }
                verifyActions(builder)
            }
        }
    }

    @Test
    fun rebuildResolvesActionLabelsAgainAfterAConfigurationChange() {
        withBuilders { builders ->
            render(CoreNotificationContent(title = "server"))
            whenever(resources.getString(R.string.notification_action_stop_v2ray)).thenReturn("停止")
            whenever(resources.getString(R.string.title_service_restart)).thenReturn("服务重启")
            render(CoreNotificationContent(title = "server"))

            assertEquals(2, builders.constructed().size)
            verifyActions(builders.constructed().first())
            val refreshed = builders.constructed().last()
            verify(refreshed).addAction(R.drawable.ic_delete_24dp, "停止", stopIntent)
            verify(refreshed).addAction(R.drawable.ic_restore_24dp, "服务重启", restartIntent)
        }
    }

    private fun render(content: CoreNotificationContent) {
        buildCoreNotification(context, "core-channel", content, contentIntent, stopIntent, restartIntent)
    }

    private fun verifyActions(builder: NotificationCompat.Builder) {
        verify(builder).setContentIntent(contentIntent)
        verify(builder).addAction(R.drawable.ic_delete_24dp, "Stop", stopIntent)
        verify(builder).addAction(R.drawable.ic_restore_24dp, "Restart service", restartIntent)
        verify(builder).setOngoing(true)
        verify(builder).setOnlyAlertOnce(true)
        verify(builder).build()
    }

    // Mock only the Android rendering boundary; the production factory and content are real.
    private fun withBuilders(block: (MockedConstruction<NotificationCompat.Builder>) -> Unit) {
        val notification = mock<Notification>()
        mockConstruction(
            NotificationCompat.Builder::class.java,
            withSettings().defaultAnswer(RETURNS_SELF)
        ) { builder, _ ->
            whenever(builder.build()).thenReturn(notification)
        }.use(block)
    }
}
