package de.solixmonitor.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class SolixRepository {
    suspend fun readSnapshot(settings: SolixSettings): SolixSnapshot = withContext(Dispatchers.IO) {
        settings.validate().getOrThrow()
        if (settings.demoMode) demoSnapshot() else liveSnapshot(settings)
    }

    private fun liveSnapshot(settings: SolixSettings): SolixSnapshot {
        var lastError: Exception? = null
        repeat(MODBUS_ATTEMPTS) { attempt ->
            try {
                return readLiveSnapshot(settings)
            } catch (error: Exception) {
                lastError = error
                if (attempt < MODBUS_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }

        val error = requireNotNull(lastError)
        if (generateSequence<Throwable>(error) { it.cause }.any { it is SocketTimeoutException }) {
            throw IllegalStateException(
                "Port ${settings.port} ist erreichbar, aber die Solarbank antwortet nicht auf Modbus. " +
                    "Prüfe, ob Modbus TCP aktiviert ist, die IP zur Solarbank gehört und kein anderer " +
                    "Modbus-Client verbunden ist.",
                error,
            )
        }
        throw error
    }

    private fun readLiveSnapshot(settings: SolixSettings): SolixSnapshot {
        ModbusTcpClient(settings.host, settings.port, settings.unitId).use { client ->
            val live = client.readInput(10000, 51)
            val totals = runCatching { client.readInput(10208, 58) }.getOrDefault(intArrayOf())
            val info = runCatching { client.readInput(10090, 67) }.getOrDefault(intArrayOf())
            val modelRegisters = runCatching { client.readInput(32768, 5) }.getOrDefault(intArrayOf())
            val controls = runCatching { client.readHolding(10060, 13) }.getOrDefault(intArrayOf())

            val batterySoc = register(live, 10000, 10014)?.toInt() ?: 0
            require(batterySoc in 0..100) { "Ungültiger Akkustand vom Gerät: $batterySoc%" }

            val batteryStatus = when (register(live, 10000, 10001)?.toInt() ?: 0) {
                1 -> "Lädt"
                2 -> "Entlädt"
                3 -> "Schlafmodus"
                else -> "Bereit"
            }
            val operatingMode = when (register(controls, 10060, 10064)?.toInt()) {
                0 -> "Eigenverbrauch"
                1 -> "Zeitplan (TOU)"
                3 -> "Drittanbieter-Steuerung"
                4 -> "Benutzerdefiniert"
                5 -> "Steckdosen-Overlay"
                6 -> "Smart-Modus"
                7 -> "Dynamischer Tarif"
                else -> "Unbekannt"
            }
            val model = decodeString(modelRegisters).ifBlank { "Solarbank 4 E5000 Pro" }
            val firmware = if (info.size >= 28) decodeString(info.copyOfRange(22, 28)).ifBlank { "–" } else "–"
            val directPv = int32(live, 10000, 10002) ?: 0
            val thirdPartyPv = int32(live, 10000, 10004) ?: 0

            return SolixSnapshot(
                timestampMs = System.currentTimeMillis(),
                deviceModel = model,
                firmware = firmware,
                connected = true,
                pvW = directPv.saturatingAdd(thirdPartyPv),
                loadW = int32(live, 10000, 10010) ?: 0,
                batteryW = int32(live, 10000, 10008) ?: 0,
                gridW = int32(live, 10000, 10012) ?: 0,
                acOutputW = int32(totals, 10208, 10208) ?: 0,
                batterySoc = batterySoc,
                batteryStatus = batteryStatus,
                operatingMode = operatingMode,
                pvTotalKwh = (uint32(live, 10000, 10018) ?: 0L) / 10.0,
                chargeTotalKwh = (uint32(totals, 10208, 10262) ?: 0L) / 10.0,
                dischargeTotalKwh = (uint32(totals, 10208, 10264) ?: 0L) / 10.0,
                ratedEnergyKwh = (uint32(totals, 10208, 10250) ?: 0L) / 10.0,
                maxChargeW = int32(live, 10000, 10036) ?: 0,
                maxDischargeW = int32(live, 10000, 10038) ?: 0,
            )
        }
    }

    private fun demoSnapshot(): SolixSnapshot {
        val now = System.currentTimeMillis()
        val phase = (now % 180_000).toDouble() / 180_000.0 * PI * 2.0
        val pv = max(0.0, 720.0 + sin(phase) * 260.0).roundToInt()
        val load = max(80.0, 410.0 + cos(phase * 1.7) * 95.0).roundToInt()
        val battery = (pv - load - 18).coerceIn(-800, 800)
        val soc = (68 + ((sin(phase) + 1.0) * 4.0).roundToInt()).coerceIn(0, 100)
        return SolixSnapshot(
            timestampMs = now,
            deviceModel = "Solarbank 4 E5000 Pro",
            firmware = "Android-Demo 0.1.1",
            connected = true,
            pvW = pv,
            loadW = load,
            batteryW = battery,
            gridW = load - pv - battery,
            acOutputW = max(0, pv - battery),
            batterySoc = soc,
            batteryStatus = if (battery > 10) "Entlädt" else if (battery < -10) "Lädt" else "Bereit",
            operatingMode = "Eigenverbrauch",
            pvTotalKwh = 426.8,
            chargeTotalKwh = 318.4,
            dischargeTotalKwh = 286.1,
            ratedEnergyKwh = 5.0,
            maxChargeW = 2400,
            maxDischargeW = 2400,
        )
    }

    internal companion object {
        private const val MODBUS_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 250L

        fun register(block: IntArray, start: Int, address: Int): Int? =
            block.getOrNull(address - start)

        fun int32(block: IntArray, start: Int, address: Int): Int? {
            val high = register(block, start, address)?.toLong() ?: return null
            val low = register(block, start, address + 1)?.toLong() ?: return null
            return ((high shl 16) or low).toInt()
        }

        fun uint32(block: IntArray, start: Int, address: Int): Long? {
            val high = register(block, start, address)?.toLong() ?: return null
            val low = register(block, start, address + 1)?.toLong() ?: return null
            return (high shl 16) or low
        }

        fun decodeString(registers: IntArray): String {
            val bytes = registers.flatMap { value ->
                listOf((value.toInt() shr 8).toByte(), value.toByte())
            }.takeWhile { it.toInt() != 0 }.toByteArray()
            return bytes.toString(Charsets.UTF_8).trim()
        }

        private fun Int.saturatingAdd(other: Int): Int =
            (toLong() + other.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
