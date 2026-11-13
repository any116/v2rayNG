package com.v2ray.ang.ui.base

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

/** Navigation and result bridge owned by each Compose back-stack entry. */
@Stable
interface AppNavigator {
    /** Results returned by the most recently popped child destination. */
    val results: Flow<BaseResult?>

    /** @return true when handled, including an explicitly logged discard from an inactive entry. */
    fun navigate(route: BaseRoute): Boolean

    /** @return true when popped, exited at the root, or explicitly discarded from an inactive entry. */
    fun finish(result: BaseResult): Boolean

    /** Claims the result before the screen delivers it to its ViewModel. */
    fun consumeResult()
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator?> { null }
