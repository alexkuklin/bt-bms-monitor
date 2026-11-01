package com.alexkuklin.jbdmonitor

/** One register read. [status] is the BMS status byte (0 = ok, 0x80 = rejected) or -1 on timeout. */
class RegisterRead(val reg: Int, val status: Int, val data: ByteArray)

/** Serialises a raw register dump. Values are not interpreted so they can be compared against other tools. */
object RegisterDump {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun esc(s: String) = buildString {
        for (c in s) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    fun toJson(deviceName: String?, appVersion: String, timestamp: String, regs: List<RegisterRead>): String =
        buildString {
            appendLine("{")
            appendLine("  \"format\": \"jbd-register-dump\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"app\": \"JBD Battery Monitor $appVersion\",")
            appendLine("  \"timestamp\": \"${esc(timestamp)}\",")
            appendLine("  \"device\": \"${esc(deviceName ?: "")}\",")
            appendLine("  \"note\": \"Raw register data, big-endian, as returned by the BMS. Not interpreted.\",")
            appendLine("  \"registers\": [")
            regs.forEachIndexed { i, r ->
                val u16 = if (r.data.size == 2) {
                    ((r.data[0].toInt() and 0xFF) shl 8) or (r.data[1].toInt() and 0xFF)
                } else null
                append("    {\"reg\": \"0x%02X\", \"status\": %d, \"hex\": \"%s\"".format(r.reg, r.status, hex(r.data)))
                if (u16 != null) append(", \"u16\": $u16")
                append(if (i < regs.lastIndex) "},\n" else "}\n")
            }
            appendLine("  ]")
            append("}")
        }
}
