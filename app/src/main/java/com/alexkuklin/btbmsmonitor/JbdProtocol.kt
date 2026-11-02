package com.alexkuklin.btbmsmonitor

/** Decoded response to the 0x03 "basic info" command. Current is positive while charging. */
data class BasicInfo(
    val packVoltage: Float,
    val current: Float,
    val remainingAh: Float,
    val nominalAh: Float,
    val cycles: Int,
    val balanceMask: Long,
    val protection: Int,
    val soc: Int,
    val fetStatus: Int,
    val cellCount: Int,
    val temps: List<Float>,
) {
    val chargeFetOn get() = fetStatus and 1 != 0
    val dischargeFetOn get() = fetStatus and 2 != 0
    val powerW get() = packVoltage * current
}

data class Frame(val cmd: Int, val status: Int, val data: ByteArray)

object JbdProtocol {
    const val CMD_BASIC = 0x03
    const val CMD_CELLS = 0x04

    val REQUEST_BASIC = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77)
    val REQUEST_CELLS = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFC.toByte(), 0x77)

    const val OP_READ = 0xA5
    const val OP_WRITE = 0x5A

    /** Registers read in factory mode by the parameter dump (raw, uninterpreted). */
    val DUMP_REGISTERS = 0x10..0x5F
    const val REG_HW_VERSION = 0x05

    /** Builds `DD op reg len data crc(2) 77`; crc = 0x10000 - (reg + len + data bytes). */
    fun request(op: Int, reg: Int, data: ByteArray = ByteArray(0)): ByteArray {
        var sum = reg + data.size
        data.forEach { sum += it.toInt() and 0xFF }
        val crc = (0x10000 - sum) and 0xFFFF
        return byteArrayOf(0xDD.toByte(), op.toByte(), reg.toByte(), data.size.toByte()) + data +
            byteArrayOf((crc shr 8).toByte(), crc.toByte(), 0x77)
    }

    fun readRequest(reg: Int) = request(OP_READ, reg)

    /** Factory-mode switch: register 0x00 <- 0x5678 enters, register 0x01 <- 0x0000 leaves. */
    val ENTER_FACTORY = request(OP_WRITE, 0x00, byteArrayOf(0x56, 0x78))
    val EXIT_FACTORY = request(OP_WRITE, 0x01, byteArrayOf(0x00, 0x00))

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.s16(i: Int) = u16(i).toShort().toInt()

    fun parseBasic(d: ByteArray): BasicInfo? {
        if (d.size < 23) return null
        val ntcCount = d.u8(22)
        if (d.size < 23 + ntcCount * 2) return null
        val temps = List(ntcCount) { (d.u16(23 + it * 2) - 2731) / 10f }
        return BasicInfo(
            packVoltage = d.u16(0) / 100f,
            current = d.s16(2) / 100f,
            remainingAh = d.u16(4) / 100f,
            nominalAh = d.u16(6) / 100f,
            cycles = d.u16(8),
            balanceMask = (d.u16(14).toLong() shl 16) or d.u16(12).toLong(),
            protection = d.u16(16),
            soc = d.u8(19),
            fetStatus = d.u8(20),
            cellCount = d.u8(21),
            temps = temps,
        )
    }

    /** Cell voltages in volts. */
    fun parseCells(d: ByteArray): List<Float> = List(d.size / 2) { d.u16(it * 2) / 1000f }

    private val protectionNames = listOf(
        "Cell overvoltage", "Cell undervoltage", "Pack overvoltage", "Pack undervoltage",
        "Charge over-temp", "Charge under-temp", "Discharge over-temp", "Discharge under-temp",
        "Charge overcurrent", "Discharge overcurrent", "Short circuit", "Front-end IC error", "MOS locked",
    )

    fun protectionFlags(mask: Int): List<String> =
        protectionNames.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
}

/**
 * BLE notifications arrive in small chunks (typically 20 bytes); this reassembles them into
 * complete `DD cmd status len data crc(2) 77` frames and drops anything with a bad checksum.
 */
class FrameAssembler {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<Frame> {
        buf += chunk
        val out = mutableListOf<Frame>()
        while (true) {
            val start = buf.indexOf(0xDD.toByte())
            if (start < 0) {
                buf = ByteArray(0)
                break
            }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 4) break
            val len = buf[3].toInt() and 0xFF
            val total = len + 7
            if (buf.size < total) break
            if (buf[total - 1] != 0x77.toByte()) {
                buf = buf.copyOfRange(1, buf.size) // false start byte, resync
                continue
            }
            var sum = 0
            for (i in 2 until 4 + len) sum += buf[i].toInt() and 0xFF
            val crc = ((buf[4 + len].toInt() and 0xFF) shl 8) or (buf[5 + len].toInt() and 0xFF)
            if (((0x10000 - sum) and 0xFFFF) == crc) {
                out += Frame(buf[1].toInt() and 0xFF, buf[2].toInt() and 0xFF, buf.copyOfRange(4, 4 + len))
            }
            buf = buf.copyOfRange(total, buf.size)
        }
        return out
    }
}
