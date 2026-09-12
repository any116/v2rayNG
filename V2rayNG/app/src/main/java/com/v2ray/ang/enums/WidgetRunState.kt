package com.v2ray.ang.enums

/** Switch state rendered by the home screen widget. */
enum class WidgetRunState {
    UNKNOWN,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    PERMISSION_REQUIRED,
    FAILED;

    /** A command was sent and the daemon has not answered yet. */
    val isPending: Boolean get() = this == STARTING || this == STOPPING

    val isActive: Boolean get() = this == RUNNING || this == STOPPING

    companion object {
        fun from(name: String?): WidgetRunState =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
