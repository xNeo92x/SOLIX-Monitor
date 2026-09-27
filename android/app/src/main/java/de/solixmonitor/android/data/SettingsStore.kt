package de.solixmonitor.android.data

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("solix_settings", Context.MODE_PRIVATE)

    fun load(): SolixSettings = SolixSettings(
        host = preferences.getString("host", null) ?: "192.168.178.100",
        port = preferences.getInt("port", 502),
        unitId = preferences.getInt("unit_id", 1),
        refreshSeconds = preferences.getInt("refresh_seconds", 5),
        demoMode = preferences.getBoolean("demo_mode", true),
    )

    fun save(settings: SolixSettings) {
        preferences.edit()
            .putString("host", settings.host.trim())
            .putInt("port", settings.port)
            .putInt("unit_id", settings.unitId)
            .putInt("refresh_seconds", settings.refreshSeconds)
            .putBoolean("demo_mode", settings.demoMode)
            .apply()
    }
}
