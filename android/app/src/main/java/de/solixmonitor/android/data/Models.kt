package de.solixmonitor.android.data

data class SolixSettings(
    val host: String = "192.168.178.100",
    val port: Int = 502,
    val unitId: Int = 1,
    val refreshSeconds: Int = 5,
    val demoMode: Boolean = true,
) {
    fun validate(): Result<SolixSettings> = when {
        !demoMode && host.isBlank() -> Result.failure(IllegalArgumentException("Bitte eine IP-Adresse eintragen."))
        port !in 1..65535 -> Result.failure(IllegalArgumentException("Der Port muss zwischen 1 und 65535 liegen."))
        unitId !in 1..247 -> Result.failure(IllegalArgumentException("Die Geräte-ID muss zwischen 1 und 247 liegen."))
        refreshSeconds !in setOf(2, 5, 10, 30, 60) -> Result.failure(IllegalArgumentException("Ungültiges Aktualisierungsintervall."))
        else -> Result.success(this)
    }
}

data class SolixSnapshot(
    val timestampMs: Long,
    val deviceModel: String,
    val firmware: String,
    val connected: Boolean,
    val pvW: Int,
    val loadW: Int,
    val batteryW: Int,
    val gridW: Int,
    val acOutputW: Int,
    val batterySoc: Int,
    val batteryStatus: String,
    val operatingMode: String,
    val pvTotalKwh: Double,
    val chargeTotalKwh: Double,
    val dischargeTotalKwh: Double,
    val ratedEnergyKwh: Double,
    val maxChargeW: Int,
    val maxDischargeW: Int,
)

data class HistoryPoint(
    val timestampMs: Long,
    val pvW: Int,
    val loadW: Int,
    val batteryW: Int,
    val gridW: Int,
)

sealed interface TestConnectionState {
    data object Idle : TestConnectionState
    data object Running : TestConnectionState
    data class Success(val message: String) : TestConnectionState
    data class Failure(val message: String) : TestConnectionState
}

data class SolixUiState(
    val settings: SolixSettings = SolixSettings(),
    val snapshot: SolixSnapshot? = null,
    val history: List<HistoryPoint> = emptyList(),
    val refreshing: Boolean = false,
    val connectionError: String? = null,
    val showSettings: Boolean = false,
    val testConnectionState: TestConnectionState = TestConnectionState.Idle,
)
