package com.v2ray.ang.ui.about

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.repository.TranslatorGroup
import com.v2ray.ang.repository.TranslatorRow
import com.v2ray.ang.ui.base.BaseScreen
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.VersionInfoBlock
import com.v2ray.ang.ui.compose.verticalScrollbar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val ListHorizontalPad = 16.dp
private val ListVerticalPad = 8.dp
private val RowHorizontalPad = 16.dp
private val RowVerticalPad = 12.dp
private val MinRowHeight = 48.dp
private val GroupSpacing = 12.dp
private val LinkIconSize = 20.dp
private val LinkIconGap = 12.dp
private val ContributorDividerThickness = 0.5.dp

private const val NameMaxLines = 2

private const val TranslatorGroupContentType = "translator-group"

@Composable
fun AboutScreen(viewModel: AboutViewModel) {
    val dispatch = remember(viewModel) { viewModel::onAction }
    val onBack = remember(dispatch) { { dispatch(AboutAction.Back) } }

    BackHandler(onBack = onBack)

    BaseScreen(
        viewModel = viewModel,
        topBar = {
            val page by rememberAboutPage(viewModel)
            AppTopBar(title = stringResource(page.titleRes), onBackClick = onBack)
        }
    ) { state, onAction ->
        when (state.page) {
            AboutPage.MENU -> AboutContent(
                versionText = state.versionText,
                appId = state.appId,
                onAction = onAction
            )
            AboutPage.TRANSLATORS -> TranslatorsContent(
                groups = state.translators,
                onAction = onAction
            )
            AboutPage.OSS_LICENSE -> AboutLicenseContent()
        }
    }
}

@Composable
private fun rememberAboutPage(viewModel: AboutViewModel): State<AboutPage> {
    val flow = remember(viewModel) {
        viewModel.uiState.map { it.page }.distinctUntilChanged()
    }
    val initial = remember(viewModel) { viewModel.uiState.value.page }
    return flow.collectAsStateWithLifecycle(initialValue = initial)
}

// ===== about menu =====

@Composable
private fun AboutContent(
    versionText: String,
    appId: String,
    onAction: (AboutAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScrollbar(scrollState)
            .verticalScroll(scrollState)
    ) {
        AboutEntry.entries.forEach { entry ->
            AboutMenuRow(entry = entry, onAction = onAction)
        }
        if (versionText.isNotEmpty()) {
            VersionInfoBlock(versionText = versionText, appIdText = appId.ifEmpty { null })
        }
        NavigationBarsSpacer()
    }
}

@Composable
private fun AboutMenuRow(
    entry: AboutEntry,
    onAction: (AboutAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val onClick = remember(entry, onAction) { { onAction(AboutAction.EntryClicked(entry)) } }
    SettingsMenuItem(
        icon = painterResource(entry.iconRes),
        title = stringResource(entry.titleRes),
        onClick = onClick,
        modifier = modifier
    )
}

// ===== translators =====

@Composable
private fun TranslatorsContent(
    groups: List<TranslatorGroup>,
    onAction: (AboutAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val onLinkClick = remember(onAction) {
        { url: String -> onAction(AboutAction.LinkClicked(url)) }
    }
    val bottomInset = NavigationBarsBottomPadding(extra = ListVerticalPad).calculateBottomPadding()
    val contentPadding = remember(bottomInset) {
        PaddingValues(
            start = ListHorizontalPad,
            top = ListVerticalPad,
            end = ListHorizontalPad,
            bottom = bottomInset
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .verticalScrollbar(listState),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(GroupSpacing)
    ) {
        items(
            items = groups,
            key = { it.language },
            contentType = { TranslatorGroupContentType }
        ) { group ->
            TranslatorGroupCard(group = group, onLinkClick = onLinkClick)
        }
    }
}

@Composable
private fun TranslatorGroupCard(
    group: TranslatorGroup,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            Text(
                text = group.language,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = RowHorizontalPad,
                    vertical = RowVerticalPad
                )
            )
            group.members.forEachIndexed { index, member ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = RowHorizontalPad),
                        thickness = ContributorDividerThickness,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                ContributorRow(
                    displayName = member.displayName,
                    url = member.url,
                    onLinkClick = onLinkClick
                )
            }
        }
    }
}

@Composable
private fun ContributorRow(
    displayName: String,
    url: String?,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLink = url != null
    val onClick = remember(url, onLinkClick) {
        { if (url != null) onLinkClick(url) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinRowHeight)
            .then(
                if (isLink) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = RowHorizontalPad, vertical = RowVerticalPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLink) {
            Icon(
                painter = painterResource(R.drawable.ic_github_24dp),
                contentDescription = null,
                modifier = Modifier.size(LinkIconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isLink) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = NameMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (isLink) LinkIconGap else 0.dp)
        )
    }
}

// ===== previews =====

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutContentPreview() = AppTheme {
    AboutContent(
        versionText = "v2.3.6 (26.2.6)",
        appId = "com.v2ray.ang",
        onAction = {}
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TranslatorsContentPreview() = AppTheme {
    TranslatorsContent(
        groups = listOf(
            TranslatorGroup(
                language = "Chinese",
                members = listOf(
                    TranslatorRow(id = "zh#0", displayName = "2dust", url = "https://github.com/2dust"),
                    TranslatorRow(id = "zh#1", displayName = "anonymous contributor", url = null)
                )
            )
        ),
        onAction = {}
    )
}
