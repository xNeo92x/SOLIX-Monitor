package de.solixmonitor.android.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

internal class ModbusTcpClient(
    host: String,
    port: Int,
    private val unitId: Int,
) : AutoCloseable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var transactionId = 0

    init {
        try {
            socket.connect(InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            socket.tcpNoDelay = true
            input = DataInputStream(socket.getInputStream())
            output = DataOutputStream(socket.getOutputStream())
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw IllegalStateException("Keine Verbindung zu ${host.trim()}:$port: ${error.message ?: "unbekannter Fehler"}", error)
        }
    }

    fun readInput(address: Int, count: Int): IntArray = readRegisters(0x04, address, count)

    fun readHolding(address: Int, count: Int): IntArray = readRegisters(0x03, address, count)

    private fun readRegisters(function: Int, address: Int, count: Int): IntArray {
        require(count in 1..125) { "Ungültige Modbus-Registeranzahl" }
        require(address in 0..65535) { "Ungültige Registeradresse" }

        transactionId = (transactionId + 1) and 0xFFFF
        output.writeShort(transactionId)
        output.writeShort(0)
        output.writeShort(6)
        output.writeByte(unitId)
        output.writeByte(function)
        output.writeShort(address)
        output.writeShort(count)
        output.flush()

        val responseTransaction = input.readUnsignedShort()
        val protocol = input.readUnsignedShort()
        val length = input.readUnsignedShort()
        val responseUnitId = input.readUnsignedByte()
        if (responseTransaction != transactionId || protocol != 0 || responseUnitId != unitId) {
            error("Ungültiger Modbus-Antwortkopf")
        }
        if (length !in 2..254) error("Ungültige Modbus-Antwortlänge")

        val payload = ByteArray(length - 1)
        input.readFully(payload)
        val responseFunction = payload.firstOrNull()?.toInt()?.and(0xFF)
            ?: error("Leere Modbus-Antwort")
        if (responseFunction == (function or 0x80)) {
            val code = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            error("Modbus-Ausnahme 0x${code.toString(16).uppercase().padStart(2, '0')} bei Register $address")
        }
        if (responseFunction != function || payload.size < 2) error("Unerwartete Modbus-Funktion in der Antwort")

        val byteCount = payload[1].toInt() and 0xFF
        val expected = count * 2
        if (byteCount != expected || payload.size < byteCount + 2) {
            error("Falsche Datenlänge: erwartet $expected, erhalten $byteCount Byte")
        }

        return IntArray(count) { index ->
            val offset = 2 + index * 2
            ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        }
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val IO_TIMEOUT_MS = 5_000
    }
}
