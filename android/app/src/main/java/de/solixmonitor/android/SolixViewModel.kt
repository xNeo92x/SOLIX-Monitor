package de.solixmonitor.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.solixmonitor.android.data.HistoryPoint
import de.solixmonitor.android.data.SettingsStore
import de.solixmonitor.android.data.SolixRepository
import de.solixmonitor.android.data.SolixSettings
import de.solixmonitor.android.data.SolixUiState
import de.solixmonitor.android.data.TestConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SolixViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SolixRepository()
    private val settingsStore = SettingsStore(application)
    private val mutableState = MutableStateFlow(SolixUiState(settings = settingsStore.load()))
    val state: StateFlow<SolixUiState> = mutableState.asStateFlow()
    private var pollingJob: Job? = null

    init {
        restartPolling()
    }

    fun refresh() {
        if (!mutableState.value.refreshing) viewModelScope.launch { refreshInternal() }
    }

    fun showSettings() {
        mutableState.value = mutableState.value.copy(
            showSettings = true,
            testConnectionState = TestConnectionState.Idle,
        )
    }

    fun hideSettings() {
        mutableState.value = mutableState.value.copy(
            showSettings = false,
            testConnectionState = TestConnectionState.Idle,
        )
    }

    fun saveSettings(settings: SolixSettings): Result<Unit> {
        return settings.validate().map {
            settingsStore.save(settings)
            mutableState.value = mutableState.value.copy(
                settings = settings,
                history = emptyList(),
                connectionError = null,
                showSettings = false,
                testConnectionState = TestConnectionState.Idle,
            )
            restartPolling()
        }
    }

    fun testConnection(settings: SolixSettings) {
        val validation = settings.validate()
        if (validation.isFailure) {
            mutableState.value = mutableState.value.copy(
                testConnectionState = TestConnectionState.Failure(
                    validation.exceptionOrNull()?.message ?: "Ungültige Einstellungen"
                )
            )
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(testConnectionState = TestConnectionState.Running)
            try {
                val snapshot = repository.readSnapshot(settings)
                mutableState.value = mutableState.value.copy(
                    testConnectionState = TestConnectionState.Success(
                        "Verbindung erfolgreich · ${snapshot.deviceModel} · ${snapshot.batterySoc}%"
                    )
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    testConnectionState = TestConnectionState.Failure(error.userMessage())
                )
            }
        }
    }

    private fun restartPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshInternal()
                delay(mutableState.value.settings.refreshSeconds * 1_000L)
            }
        }
    }

    private suspend fun refreshInternal() {
        mutableState.value = mutableState.value.copy(refreshing = true)
        try {
            val snapshot = repository.readSnapshot(mutableState.value.settings)
            val cutoff = System.currentTimeMillis() - 3_600_000L
            val newPoint = HistoryPoint(
                timestampMs = snapshot.timestampMs,
                pvW = snapshot.pvW,
                loadW = snapshot.loadW,
                batteryW = snapshot.batteryW,
                gridW = snapshot.gridW,
            )
            val maximumPoints = (3_600 / mutableState.value.settings.refreshSeconds + 4).coerceIn(120, 1_800)
            val history = (mutableState.value.history + newPoint)
                .filter { it.timestampMs >= cutoff }
                .distinctBy { it.timestampMs }
                .takeLast(maximumPoints)
            mutableState.value = mutableState.value.copy(
                snapshot = snapshot,
                history = history,
                refreshing = false,
                connectionError = null,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                refreshing = false,
                connectionError = error.userMessage(),
            )
        }
    }

    private fun Throwable.userMessage(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
            ?: "Unbekannter Verbindungsfehler"
}
