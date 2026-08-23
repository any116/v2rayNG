package com.v2ray.ang.ui.server

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.horizontalScrollbar
import com.v2ray.ang.ui.compose.verticalScrollbar
import kotlinx.coroutines.flow.collectLatest

private object EditorDimens {
    val GutterPadding = 8.dp
    val ScrollbarThickness = 4.dp
    val ScrollbarPadding = 2.dp
    val ScrollPadding = 60.dp
    val EditorEndPadding = 24.dp
}

@Composable
internal fun ServerJsonEditor(
    remarks: String,
    rawContent: TextFieldState,
    onAction: (ServerAction) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val baseStyle = MaterialTheme.typography.bodyMedium
    val editorColor = MaterialTheme.colorScheme.onSurface
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val editorStyle = remember(baseStyle, editorColor) {
        baseStyle.copy(fontFamily = FontFamily.Monospace, color = editorColor)
    }
    val placeholderStyle = remember(editorStyle) {
        editorStyle.copy(color = editorColor.copy(alpha = 0.38f))
    }
    val gutterStyle = remember(editorStyle, gutterColor) {
        editorStyle.copy(color = gutterColor, textAlign = TextAlign.End)
    }

    val layoutRef = remember { mutableStateOf<TextLayoutResult?>(null) }
    var lineCount by remember { mutableIntStateOf(1) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val gutterCache = remember(gutterStyle) { HashMap<Int, TextLayoutResult>() }

    val gutterWidth = remember(lineCount, gutterStyle, density, textMeasurer) {
        val digits = lineCount.toString().length.coerceAtLeast(1)
        val measured = textMeasurer.measure("0".repeat(digits), gutterStyle)
        with(density) { measured.size.width.toDp() + EditorDimens.GutterPadding * 2 }
    }
    val isEmpty by remember { derivedStateOf { rawContent.text.isEmpty() } }

    LaunchedEffect(rawContent, verticalScroll, horizontalScroll) {
        snapshotFlow {
            Triple(rawContent.selection, verticalScroll.viewportSize, horizontalScroll.viewportSize)
        }.collectLatest { (selection, _, _) ->
            val layout = layoutRef.value ?: return@collectLatest
            val cursor = selection.start
            val textLen = layout.layoutInput.text.length
            if (textLen == 0 || cursor < 0 || cursor > textLen) return@collectLatest

            val line = layout.getLineForOffset(cursor)
            val lineTop = layout.getLineTop(line)
            val lineBottom = layout.getLineBottom(line)
            val pad = with(density) { EditorDimens.ScrollPadding.toPx() }

            val vh = verticalScroll.viewportSize.toFloat()
            if (vh > 0f) {
                val scrollY = verticalScroll.value.toFloat()
                val targetY = when {
                    lineBottom > scrollY + vh - pad -> (lineBottom - vh + pad).toInt()
                    lineTop < scrollY + pad -> (lineTop - pad).toInt()
                    else -> null
                }
                targetY?.let {
                    verticalScroll.animateScrollTo(it.coerceIn(0, verticalScroll.maxValue))
                }
            }

            val cursorX = layout.getHorizontalPosition(cursor, true)
            val vw = horizontalScroll.viewportSize.toFloat()
            if (vw > 0f) {
                val scrollX = horizontalScroll.value.toFloat()
                val targetX = when {
                    cursorX < scrollX + pad -> (cursorX - pad).toInt().coerceAtLeast(0)
                    cursorX > scrollX + vw - pad ->
                        (cursorX - vw + pad).toInt().coerceAtMost(horizontalScroll.maxValue)
                    else -> null
                }
                targetX?.let { horizontalScroll.animateScrollTo(it) }
            }
        }
    }

    Column(modifier = modifier) {
        FormTextField(
            label = stringResource(R.string.server_lab_remarks),
            value = remarks,
            onValueChange = { onAction(ServerAction.TextChanged(ServerField.REMARKS, it)) },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding)
            ) {
                if (contentHeight > 0) {
                    Canvas(
                        modifier = Modifier
                            .width(gutterWidth)
                            .height(with(density) { contentHeight.toDp() })
                    ) {
                        val layout = layoutRef.value ?: return@Canvas
                        val padPx = EditorDimens.GutterPadding.toPx()
                        val top = verticalScroll.value.toFloat()
                        val viewport = verticalScroll.viewportSize
                            .toFloat()
                            .takeIf { it > 0f } ?: size.height
                        val firstLine = layout.getLineForVerticalPosition(top)
                        val lastLine = layout.getLineForVerticalPosition(top + viewport)
                        for (i in firstLine..lastLine.coerceAtMost(layout.lineCount - 1)) {
                            val number = i + 1
                            val measured = gutterCache.getOrPut(number) {
                                textMeasurer.measure(number.toString(), gutterStyle)
                            }
                            val y = layout.getLineBaseline(i) - measured.firstBaseline
                            val x = size.width - padPx - measured.size.width
                            drawText(measured, topLeft = Offset(x.coerceAtLeast(0f), y))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .width(gutterWidth)
                            .padding(end = EditorDimens.GutterPadding),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Text(text = "1", style = gutterStyle)
                    }
                }

                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(
                        handleColor = MaterialTheme.colorScheme.secondary,
                        backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                    )
                ) {
                    BasicTextField(
                        state = rawContent,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                            .padding(end = EditorDimens.EditorEndPadding),
                        textStyle = editorStyle,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                        onTextLayout = { provider ->
                            provider()?.let { result ->
                                layoutRef.value = result
                                if (result.lineCount != lineCount) lineCount = result.lineCount
                                if (result.size.height != contentHeight) {
                                    contentHeight = result.size.height
                                }
                            }
                        },
                        decorator = { innerTextField ->
                            Box {
                                if (isEmpty) Text(text = "{ }", style = placeholderStyle)
                                innerTextField()
                            }
                        },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(EditorDimens.ScrollbarThickness + EditorDimens.ScrollbarPadding * 2)
                    .verticalScrollbar(scrollState = verticalScroll)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(EditorDimens.ScrollbarThickness + EditorDimens.ScrollbarPadding * 2)
                    .horizontalScrollbar(scrollState = horizontalScroll)
            )
        }
    }
}
