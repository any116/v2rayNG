package com.v2ray.ang.ui.main

import androidx.paging.LoadState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainScrollRestoreTest {

    @Test
    fun anEmptyQueryIsTheUnfilteredScopeNotAWildcard() {
        assertTrue(fitsQueryScope(ScrollAnchor(4, 0, query = ""), ""))
        assertFalse(fitsQueryScope(ScrollAnchor(4, 0, query = ""), "hk"))
        assertFalse(fitsQueryScope(ScrollAnchor(4, 0, query = "hk"), ""))
        assertTrue(fitsQueryScope(ScrollAnchor(4, 0, query = "hk"), "hk"))
    }

    @Test
    fun anUnstartedPageIsNotSettled() {
        assertFalse(
            isPageSettled(
                refresh = NotLoading,
                append = NotLoading,
                itemCount = 0,
                presentedQuery = null,
                expectedQuery = ""
            )
        )
        assertFalse(
            isPageSettled(
                refresh = LoadState.Loading,
                append = NotLoading,
                itemCount = 0,
                presentedQuery = null,
                expectedQuery = ""
            )
        )
    }

    @Test
    fun anEmptyPageSettlesFromTheForwardEndNotFromRefresh() {
        // REFRESH.endOfPaginationReached is always false, so it must not gate settlement.
        assertFalse(
            isPageSettled(
                refresh = LoadState.NotLoading(endOfPaginationReached = true),
                append = NotLoading,
                itemCount = 0,
                presentedQuery = null,
                expectedQuery = ""
            )
        )
        assertTrue(
            isPageSettled(
                refresh = NotLoading,
                append = Ended,
                itemCount = 0,
                presentedQuery = null,
                expectedQuery = ""
            )
        )
    }

    @Test
    fun aPageSettlesOnlyForItsOwnQueryScope() {
        assertTrue(isPageSettled(NotLoading, Ended, 12, "hk", "hk"))
        assertFalse(isPageSettled(NotLoading, Ended, 12, "hk", ""))
        assertFalse(isPageSettled(NotLoading, Ended, 12, "", "hk"))
        // A placeholder row carries no query yet, so the generation has not been presented.
        assertFalse(isPageSettled(NotLoading, Ended, 12, null, ""))
        assertTrue(isPageSettled(LoadState.Error(IllegalStateException("db")), Ended, 0, null, ""))
    }

    private companion object {
        val NotLoading = LoadState.NotLoading(endOfPaginationReached = false)
        val Ended = LoadState.NotLoading(endOfPaginationReached = true)
    }
}
