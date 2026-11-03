package dev.ryuya.galaxycharge

import org.junit.Assert.*
import org.junit.Test

class ChargeAssessmentTest {
    private fun property(key: String, vararg values: Long) =
        Property(
            key,
            values
                .flatMap { value ->
                    (3 downTo 0).map { shift -> (value ushr (8 * shift)).toByte() }
                }
                .toByteArray(),
        )

    private val table =
        listOf(
            property("battery,dc_step_chg_step", 4),
            property("battery,dc_step_chg_val_iout", 12000, 10420, 7590, 4680),
        )

    private fun sample(
        raw: String = "FCC: VOTER_CABLE: en=1 v=4680 p=0\nFCC: voter=VOTER_CABLE type=Min v=4680",
        fields: Map<String, String> = emptyMap(),
    ) =
        Snapshot(
            1,
            mapOf(
                "status" to "Charging",
                "direct_charging_status" to "1",
                "direct_charging_step" to "3",
                "direct_charging_chg_status" to "CC_MODE",
                "temp" to "285",
                "lrp" to "312",
                "voter_status" to raw,
            ) + fields,
            true,
        )

    @Test
    fun explainsLateStepOnlyWhenTheLiveSettingsAndSelectedLimitMatch() {
        val a = ChargeAssessment.from(sample(), table)
        assertEquals(ChargeReason.LATE_STEP, a.reason)
        assertTrue(a.step!!.matchesCurrentLimit)
        assertTrue(a.step.inferredFromCable)
        assertEquals(4680L, a.step.currentMa)
        assertTrue(a.thermalRequests.isEmpty())
    }

    @Test
    fun temperaturesAloneNeverActivateThermalDiagnosis() {
        val a = ChargeAssessment.from(sample(fields = mapOf("lrp" to "650")), table)
        assertEquals(ChargeReason.LATE_STEP, a.reason)
        assertTrue(a.thermalRequests.isEmpty())
    }

    @Test
    fun thermalSelectionTakesPriorityAndPreservesSimultaneousStepEvidence() {
        val raw =
            """
            FCC: VOTER_CABLE: en=1 v=4680 p=0
            FCC: voter=VOTER_CABLE type=Min v=4680
            ICL: VOTER_LRP_TEMP: en=1 v=550 p=0
            ICL: voter=VOTER_LRP_TEMP type=Min v=550
            """
                .trimIndent()
        val a = ChargeAssessment.from(sample(raw, mapOf("lrp" to "350")), table)
        assertEquals(ChargeReason.THERMAL, a.reason)
        assertTrue(a.thermalRequests.single().selected)
        assertTrue(a.step!!.matchesCurrentLimit)
    }

    @Test
    fun enabledButUnselectedThermalRequestIsNotTheReportedMainCause() {
        val raw =
            """
            FCC: VOTER_LRP_TEMP: en=1 v=8200 p=0
            FCC: VOTER_CABLE: en=1 v=4680 p=0
            FCC: voter=VOTER_CABLE type=Min v=4680
            """
                .trimIndent()
        val a = ChargeAssessment.from(sample(raw), table)
        assertEquals(ChargeReason.LATE_STEP, a.reason)
        assertFalse(a.thermalRequests.single().selected)
    }

    @Test
    fun rejectsMismatchedMissingOutOfRangeAndInactiveStepData() {
        val cases =
            listOf(
                sample(fields = mapOf("direct_charging_step" to "4")) to table,
                sample(fields = mapOf("direct_charging_step" to "-1")) to table,
                sample(fields = mapOf("direct_charging_status" to "0")) to table,
                sample() to emptyList(),
                sample() to listOf(table[0], property("battery,dc_step_chg_val_iout", 4680)),
                sample("FCC: VOTER_CABLE: en=1 v=4000\nFCC: voter=VOTER_CABLE type=Min v=4000") to
                    table,
            )
        for ((s, properties) in cases) {
            val a = ChargeAssessment.from(s, properties)
            assertEquals(ChargeReason.UNKNOWN, a.reason)
        }
    }

    @Test
    fun ignoresOldVotesWhenUnpluggedFullOrRootIsUnavailable() {
        val thermal = "FCC: VOTER_LRP_TEMP: en=1 v=2200\nFCC: voter=VOTER_LRP_TEMP type=Min v=2200"
        for ((status, reason) in
            listOf(
                "Discharging" to ChargeReason.NOT_CHARGING,
                "Not charging" to ChargeReason.NOT_CHARGING,
                "Full" to ChargeReason.FULL,
            )) {
            val a = ChargeAssessment.from(sample(thermal, mapOf("status" to status)), table)
            assertEquals(reason, a.reason)
            assertTrue(a.controls.isEmpty())
        }
        assertEquals(
            ChargeReason.BASIC_ONLY,
            ChargeAssessment.from(sample(thermal).copy(root = false), table).reason,
        )
    }

    @Test
    fun constantVoltageIsObservedInsteadOfAttributingAllTaperToTheStep() {
        val a =
            ChargeAssessment.from(
                sample(fields = mapOf("direct_charging_chg_status" to "CV_MODE")),
                table,
            )
        assertEquals(ChargeReason.CONSTANT_VOLTAGE, a.reason)
    }

    @Test
    fun inputPolicyAndUnknownVotersAreNotMislabelledAsTemperature() {
        val usb = "FCC: VOTER_USB_100MA: en=1 v=100\nFCC: voter=VOTER_USB_100MA type=Min v=100"
        assertEquals(ChargeReason.OTHER_CONTROL, ChargeAssessment.from(sample(usb), table).reason)
        val unknown = "FCC: VOTER_FUTURE: en=1 v=4680\nFCC: voter=VOTER_FUTURE type=Min v=4680"
        assertEquals(ChargeReason.UNKNOWN, ChargeAssessment.from(sample(unknown), table).reason)
    }

    @Test
    fun contradictoryReportsAndMissingVoteBodiesAreNotConfirmedSelections() {
        val raw = "FCC: VOTER_LRP_TEMP: en=1 v=8200\nFCC: voter=VOTER_LRP_TEMP type=Min v=2200"
        val a = ChargeAssessment.from(sample(raw), table)
        assertEquals(ChargeReason.UNKNOWN, a.reason)
        assertFalse(a.controls.single().selected)
        assertEquals(
            ChargeReason.UNKNOWN,
            ChargeAssessment.from(
                    sample("FCC: voter=VOTER_CABLE type=Min v=4680"),
                    table,
                )
                .reason,
        )
    }

    @Test
    fun lastIndexAloneIsNotProofThatTheCurrentWasReduced() {
        val flat =
            listOf(table[0], property("battery,dc_step_chg_val_iout", 4680, 4680, 4680, 4680))
        assertEquals(ChargeReason.UNKNOWN, ChargeAssessment.from(sample(), flat).reason)
    }

    @Test
    fun staleDirectModeAndNegativeThermalSentinelsDoNotBecomeCauses() {
        val stale =
            sample(
                fields =
                    mapOf(
                        "direct_charging_status" to "0",
                        "direct_charging_chg_status" to "CV_MODE",
                    )
            )
        assertEquals(ChargeReason.UNKNOWN, ChargeAssessment.from(stale, table).reason)
        val error = "FCC: VOTER_LRP_TEMP: en=1 v=-22\nFCC: voter=VOTER_LRP_TEMP type=Min v=-22"
        assertEquals(ChargeReason.UNKNOWN, ChargeAssessment.from(sample(error), table).reason)
    }
}
