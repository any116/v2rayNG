package com.v2ray.ang.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.v2ray.ang.data.Prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Two-way bound preference state for small standalone switches that have no ViewModel of their
 * own. Screens with a ViewModel must route the write through it instead.
 *
 * The initial read is a snapshot read; drop(1) keeps that value from being written straight back.
 */
@Composable
fun rememberSettingBool(key: String, default: Boolean = false): MutableState<Boolean> {
    val state = remember(key) { mutableStateOf(Prefs.bool(key, default)) }
    LaunchedEffect(key) {
        snapshotFlow { state.value }
            .drop(1)
            .distinctUntilChanged()
            .collectLatest { Prefs.putBool(key, it) }
    }
    return state
}

@Composable
fun rememberSettingString(key: String, default: String = ""): MutableState<String> {
    val state = remember(key) { mutableStateOf(Prefs.string(key, default) ?: default) }
    LaunchedEffect(key) {
        snapshotFlow { state.value }
            .drop(1)
            .distinctUntilChanged()
            .collectLatest { Prefs.putString(key, it) }
    }
    return state
}
