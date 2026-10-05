package com.v2ray.ang.ui.logcat

import androidx.compose.runtime.Immutable
import com.v2ray.ang.dto.LogcatRecord
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val epochPrefix = Regex("""^\s*(\d{1,12})\.(\d{3,9})\s""")
private val displayTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

/** Separator of the `TAG(pid): message` shape produced by `logcat -v time`. */
private const val TAG_SEPARATOR = "):"

@Immutable
data class LogLine(
    val id: Long,
    val tag: String,
    val content: String,
    val raw: String
) : java.io.Serializable

@Immutable
data class LogcatUiState(
    val query: String = "",
    val searchActive: Boolean = false
) : BaseUiState

sealed interface LogcatAction : BaseAction {
    data object Started : LogcatAction
    data object Stopped : LogcatAction
    data object Back : LogcatAction
    data object Refresh : LogcatAction
    data object CopyAll : LogcatAction
    data object Clear : LogcatAction
    data object Share : LogcatAction

    data object SearchOpened : LogcatAction
    data object SearchClosed : LogcatAction
    data class QueryChanged(val value: String) : LogcatAction

    data class LineLongPressed(val text: String) : LogcatAction
    data class ShareFinished(val ok: Boolean) : LogcatAction
}

/** Platform capability; translated by [LogcatActivity.handlePlatformEvent]. */
sealed interface LogcatEvent : BaseEvent.Platform {
    data class ShareFile(val path: String) : LogcatEvent
}

/**
 * Splits `TAG(pid): message` once.
 */
internal fun parseLogLine(record: LogcatRecord): LogLine {
    val line = record.raw
    val timeFormat = displayTime.withZone(ZoneId.systemDefault())
    val marker = line.indexOf(TAG_SEPARATOR)
    val rawHead = if (marker >= 0) line.substring(0, marker) else line
    val epoch = epochPrefix.find(rawHead)
    val head = if (epoch == null) rawHead else {
        val instant = Instant.ofEpochSecond(
            epoch.groupValues[1].toLong(),
            epoch.groupValues[2].padEnd(9, '0').toLong()
        )
        timeFormat.format(instant) + " " + rawHead.substring(epoch.range.last + 1)
    }
    val paren = head.indexOf('(')
    val tag = if (paren >= 0) head.substring(0, paren) else head
    val content = if (marker >= 0) {
        line.substring(marker + TAG_SEPARATOR.length).trim()
    } else {
        ""
    }
    return LogLine(
        id = record.id,
        tag = tag.trim(),
        content = content,
        raw = line
    )
}
