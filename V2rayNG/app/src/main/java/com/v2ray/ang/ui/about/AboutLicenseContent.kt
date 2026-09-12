package com.v2ray.ang.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import com.mikepenz.aboutlibraries.ui.compose.variant.LibrariesDensity
import com.mikepenz.aboutlibraries.ui.compose.variant.LibrariesVariant
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryActionMode
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.verticalScrollbar

/** res/raw/aboutlibraries.json */
@Composable
fun AboutLicenseContent(modifier: Modifier = Modifier) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val listState = rememberLazyListState()
    val contentPadding = NavigationBarsBottomPadding()

    if (libraries == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }
        return
    }

    LibrariesContainer(
        libraries = libraries,
        modifier = modifier
            .fillMaxSize()
            .verticalScrollbar(listState),
        lazyListState = listState,
        contentPadding = contentPadding,
        colors = LibraryDefaults.libraryColors(
            libraryBackgroundColor = MaterialTheme.colorScheme.surface
        ),
        variant = LibrariesVariant.Refined,
        density = LibrariesDensity.Compact,
        detailMode = LibraryDetailMode.Sheet,
        actionMode = LibraryActionMode.Icons,
        licenseDialogConfirmText = stringResource(R.string.action_ok)
    )
}
