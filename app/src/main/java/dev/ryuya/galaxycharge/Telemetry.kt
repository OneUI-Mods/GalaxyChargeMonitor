package dev.ryuya.galaxycharge

internal data class Vote(val group: String, val name: String, val value: Long, val winner: Boolean)

internal data class VoteResult(val group: String, val name: String, val value: Long)

internal data class Snapshot(
    val time: Long,
    val fields: Map<String, String>,
    val root: Boolean,
    val error: String? = null,
) {
    fun number(name: String): Double? =
        fields[name]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun temperature(name: String): Double? = number(name)?.div(10)?.takeIf { it in -50.0..150.0 }

    fun text(name: String): String? =
        fields[name]?.takeIf { it.isNotBlank() && it != "UNAVAILABLE" }

    fun batteryWatts(): Double? {
        val voltage = number("voltage_now")?.takeIf { it > 0 } ?: return null
        return number("current_now")?.let { voltage * it / 1e12 }
    }

    fun votes(): List<Vote> = VoteParser.parse(fields["voter_status"].orEmpty())

    fun voteResults(): List<VoteResult> = VoteParser.results(fields["voter_status"].orEmpty())
}

internal object VoteParser {
    private val winning = Regex("^([A-Z_]+): voter=([^ ]+) type=[^ ]+ v=(-?\\d+)\\s*$")
    private val active = Regex("^([A-Z_]+): ([^:]+):\\s+en=(\\d+) v=(-?\\d+)(?: p=.*)?\\s*$")

    fun results(raw: String): List<VoteResult> =
        raw.lineSequence()
            .mapNotNull { line ->
                val m = winning.matchEntire(line.trim()) ?: return@mapNotNull null
                val value = m.groupValues[3].toLongOrNull() ?: return@mapNotNull null
                VoteResult(m.groupValues[1], m.groupValues[2], value)
            }
            .toList()

    fun parse(raw: String): List<Vote> {
        val winners =
            raw.lineSequence()
                .mapNotNull { winning.matchEntire(it.trim()) }
                .associate { it.groupValues[1] to it.groupValues[2] }
        return raw.lineSequence()
            .mapNotNull { line ->
                val m = active.matchEntire(line.trim()) ?: return@mapNotNull null
                if (m.groupValues[3] != "1") return@mapNotNull null
                val v = m.groupValues[4].toLongOrNull() ?: return@mapNotNull null
                Vote(
                    m.groupValues[1],
                    m.groupValues[2],
                    v,
                    winners[m.groupValues[1]] == m.groupValues[2],
                )
            }
            .toList()
    }
}

internal object Frames {
    fun parse(raw: String, header: String): Map<String, String> {
        require(raw.length <= 2_000_000) { "Response too large" }
        val lines = raw.lineSequence().toList()
        val start = lines.indexOf(header)
        require(start >= 0 && lines.getOrNull(start + 1)?.trim() == "0") {
            "Root access unavailable"
        }
        val end = lines.indexOfLast { it == "GCM_END" }
        require(end > start + 1) { "Incomplete response" }
        val out = linkedMapOf<String, String>()
        var key: String? = null
        val value = StringBuilder()
        fun commit() {
            key?.let {
                require(!out.containsKey(it))
                out[it] = value.toString().trim()
            }
            value.clear()
        }
        for (line in lines.subList(start + 2, end)) {
            if (line.startsWith("@gcm:")) {
                commit()
                key = line.removePrefix("@gcm:")
                require(key!!.matches(Regex("[a-zA-Z0-9_,.-]{1,160}"))) { "Invalid field" }
                require(out.size < 1024)
            } else if (key != null) value.append(line).append('\n')
        }
        commit()
        return out
    }

    fun snapshot(raw: String): Snapshot {
        val values = parse(raw, "GCM_SNAPSHOT/1")
        require(values.keys.all { it == "clock" || it in ReadCommands.fields })
        return Snapshot(
            values["clock"]?.toLongOrNull()?.times(1000) ?: System.currentTimeMillis(),
            values,
            true,
        )
    }
}

internal data class Property(val key: String, val bytes: ByteArray) {
    val presence
        get() = bytes.isEmpty()

    fun numbers(): List<Long>? {
        if (bytes.isEmpty() || bytes.size % 4 != 0 || strings() != null) return null
        return bytes.asList().chunked(4).map { chunk ->
            chunk.fold(0L) { v, b -> (v shl 8) or (b.toInt() and 255).toLong() }
        }
    }

    fun strings(): List<String>? {
        val textual =
            key.substringAfter(',') in
                setOf(
                    "name",
                    "compatible",
                    "status",
                    "vendor",
                    "chip_vendor",
                    "io-channel-names",
                    "pinctrl-names",
                ) || key.endsWith("_name")
        if (!textual) return null
        if (bytes.isEmpty() || bytes.last() != 0.toByte() || bytes.none { it.toInt() in 32..126 })
            return null
        if (bytes.any { it != 0.toByte() && it.toInt() !in 32..126 }) return null
        return bytes.toString(Charsets.US_ASCII).trimEnd('\u0000').split('\u0000')
    }

    fun display(): String =
        when {
            presence -> "∅"
            strings() != null -> strings()!!.joinToString(" · ")
            numbers() != null -> numbers()!!.joinToString(", ")
            else -> hex()
        }

    fun hex(): String = bytes.joinToString(" ") { "%02x".format(it.toInt() and 255) }
}

internal object PropertyParser {
    fun parse(raw: String): List<Property> =
        Frames.parse(raw, "GCM_CATALOG/1")
            .map { (key, value) ->
                val tokens = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(tokens.size <= 16384 && tokens.all { it.matches(Regex("[a-fA-F0-9]{2}")) })
                Property(key, tokens.map { it.toInt(16).toByte() }.toByteArray())
            }
            .sortedBy { it.key }
}

internal data class LrpThresholds(
    val first: Double,
    val firstRecovery: Double,
    val second: Double,
    val secondRecovery: Double,
    val reset: Double?,
) {
    companion object {
        fun from(property: Property?, screenOn: Boolean): LrpThresholds? {
            val v = property?.numbers() ?: return null
            if (v.size != 12 && v.size != 15) return null
            val a = if (screenOn) 6 else 2
            val b = if (screenOn) 4 else 0
            return LrpThresholds(
                v[a] / 10.0,
                v[a + 1] / 10.0,
                v[b] / 10.0,
                v[b + 1] / 10.0,
                if (v.size == 15) v[if (screenOn) 9 else 8] / 10.0 else null,
            )
        }
    }
}
