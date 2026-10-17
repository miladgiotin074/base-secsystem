package com.base.realtime.access

internal enum class TapMode {
    /** No tapping — phase done or waiting. */
    IDLE,

    /** Runtime permission dialog — permission UI only; no blind Switch taps. */
    RUNTIME_DIALOG,

    /** Battery unrestricted Allow / radio. */
    SETTINGS_ALLOW,

    /** Exact-alarm switch page — switch with related label. */
    SETTINGS_SWITCH,

    /**
     * OEM list (e.g. Xiaomi Autostart): find app row by name and enable toggle.
     */
    OEM_APP_LIST,
}
