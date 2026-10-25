package com.v2ray.ang.ui.base

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

/** Navigation bridge shared by Compose destinations and legacy Activity wrappers. */
@Stable
interface AppNavigator {
    /** Results returned by the most recently popped child destination. */
    val results: Flow<BaseResult?>

    /** @return true when an application route was handled by the Compose graph. */
    fun navigate(route: BaseRoute): Boolean

    /** @return true when the current destination was popped by the Compose graph. */
    fun finish(result: BaseResult): Boolean

    /** Clears the result after the current screen has delivered it to its ViewModel. */
    fun consumeResult()
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator?> { null }
