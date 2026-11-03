package dev.ryuya.galaxycharge

import org.junit.Assert.*
import org.junit.Test

class TelemetryTest {
    @Test
    fun separatesBatteryPowerFromControlLimitsAndKeepsDischargeSign() {
        val s = Snapshot(0, mapOf("voltage_now" to "4200000", "current_now" to "-1000000"), true)
        assertEquals(-4.2, s.batteryWatts()!!, 0.0001)
        assertNull(
            s.copy(fields = mapOf("voltage_now" to "0", "current_now" to "2000000")).batteryWatts()
        )
        assertNull(s.copy(fields = mapOf("temp" to "NaN")).temperature("temp"))
    }

    @Test
    fun parsesAllActiveVotesAndIdentifiesTheWinningOne() {
        val votes =
            VoteParser.parse(
                "FCC: VOTER_CABLE:\t\t\ten=1 v=12000 p=0\nFCC: VOTER_LRP:\t\ten=1 v=8200 p=0\nFCC: OLD: en=0 v=100 p=0\nFCC: voter=VOTER_LRP type=Min v=8200"
            )
        assertEquals(2, votes.size)
        assertEquals(listOf(false, true), votes.map { it.winner })
    }

    @Test
    fun preservesNumericZeroAndEmptyPresenceFlagsAsDifferentTypes() {
        val p =
            PropertyParser.parse(
                "GCM_CATALOG/1\n0\n@gcm:battery,zero\n00 00 00 00\n@gcm:battery,flag\n\nGCM_END\n"
            )
        assertTrue(p.first { it.key.endsWith("flag") }.presence)
        assertEquals(listOf(0L), p.first { it.key.endsWith("zero") }.numbers())
    }

    @Test
    fun readsBigEndianAndNeverExecutesPropertyContent() {
        val p = Property("value", byteArrayOf(-1, -1, -1, -50))
        assertEquals(listOf(4294967246L), p.numbers())
        val str = Property("battery,vendor", "x;reboot\u0000".toByteArray())
        assertEquals(listOf("x;reboot"), str.strings())
        assertEquals(
            listOf(536870912L),
            Property("battery,bitmask", byteArrayOf(32, 0, 0, 0)).numbers(),
        )
    }

    @Test
    fun usesTheLiveTableAndScreenSpecificRecoveryValues() {
        val v = listOf(412, 402, 398, 388, 398, 388, 378, 368, 360, 360, 2050, 8200, 550, 2200, 800)
        val bytes =
            v.flatMap { x ->
                    listOf(
                        (x ushr 24).toByte(),
                        (x ushr 16).toByte(),
                        (x ushr 8).toByte(),
                        x.toByte(),
                    )
                }
                .toByteArray()
        val p = Property("battery,temp_table_LRP_60W_V2", bytes)
        assertEquals(37.8, LrpThresholds.from(p, true)!!.first, 0.001)
        assertEquals(38.8, LrpThresholds.from(p, false)!!.firstRecovery, 0.001)
        assertNull(LrpThresholds.from(Property("bad", byteArrayOf(0, 0, 0, 1)), true))
    }

    @Test
    fun retainsDefaultEffectiveVotesEvenWithoutAnActiveNamedVote() {
        val r =
            VoteParser.results(
                "MAX_APDO_VOLT: INIT: v=20000\nMAX_APDO_VOLT: voter=None type=Min v=20000\nMPP_PLIM: voter=None type=Min v=-22"
            )
        assertEquals(2, r.size)
        assertEquals(20000L, r[0].value)
        assertEquals(-22L, r[1].value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteRootFrames() {
        Frames.snapshot("GCM_SNAPSHOT/1\n0\n@gcm:capacity\n40")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRootResponse() {
        Frames.snapshot("GCM_SNAPSHOT/1\n2000\nGCM_END")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedCatalogHex() {
        PropertyParser.parse("GCM_CATALOG/1\n0\n@gcm:bad\nreboot\nGCM_END")
    }

    @Test
    fun commandsOnlyReadFixedPathsAndNeverContainSettingsWrites() {
        for (c in listOf(ReadCommands.snapshot, ReadCommands.catalog)) {
            for (bad in
                listOf(
                    "setprop",
                    "settings put",
                    "tee ",
                    "chmod",
                    "chown",
                    "reboot",
                    "kill ",
                    "input ",
                    "dmesg",
                    "logcat",
                )) assertFalse(bad, c.contains(bad))
            assertFalse(Regex("(?<![0-9])>(?!/dev/null)").containsMatchIn(c))
        }
        assertTrue(ReadCommands.fields.size >= 40)
        assertTrue(ReadCommands.fields.all { it.matches(Regex("[a-z0-9_]+")) })
    }
}
