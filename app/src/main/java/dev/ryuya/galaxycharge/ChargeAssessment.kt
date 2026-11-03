package dev.ryuya.galaxycharge

internal enum class ChargeReason {
    NOT_CHARGING,
    FULL,
    BASIC_ONLY,
    THERMAL,
    PROTECTION,
    OTHER_CONTROL,
    CONSTANT_VOLTAGE,
    LATE_STEP,
    STEP,
    UNKNOWN,
}

internal enum class ControlKind {
    LRP,
    CHARGER_TEMPERATURE,
    MIXED_TEMPERATURE,
    BATTERY_PROTECTION,
    CHARGE_LEVEL,
    USAGE_POLICY,
    INPUT_ADJUSTMENT,
    USB_LIMIT,
    STEP,
    CABLE,
    AGING,
    DIRECT_MODE,
    UNKNOWN,
}

internal data class ControlEvidence(val vote: Vote, val kind: ControlKind, val selected: Boolean)

internal data class StepEvidence(
    val index: Int,
    val count: Int,
    val currentMa: Long,
    val maximumMa: Long,
    val matchesCurrentLimit: Boolean,
    val inferredFromCable: Boolean,
) {
    val reduced: Boolean
        get() = currentMa < maximumMa

    val last: Boolean
        get() = index == count - 1
}

/** Evidence from one sample. Temperatures alone never identify an active limiter. */
internal data class ChargeAssessment(
    val reason: ChargeReason,
    val controls: List<ControlEvidence> = emptyList(),
    val step: StepEvidence? = null,
    val votesAvailable: Boolean = false,
) {
    val thermalRequests: List<ControlEvidence>
        get() = controls.filter { it.kind in thermalKinds }

    companion object {
        private val thermalKinds =
            setOf(ControlKind.LRP, ControlKind.CHARGER_TEMPERATURE, ControlKind.MIXED_TEMPERATURE)

        private val controlGroups = setOf("FCC", "ICL", "CHGEN", "DCHG_OP", "FV", "DCFV")

        fun from(snapshot: Snapshot, properties: List<Property>): ChargeAssessment {
            // Old readings are not interpreted as active limits after unplugging or filling up.
            if (snapshot.text("status") == "Full") return ChargeAssessment(ChargeReason.FULL)
            if (snapshot.text("status") != "Charging")
                return ChargeAssessment(
                    if (
                        snapshot.text("status") == "Discharging" ||
                            snapshot.text("status") == "Not charging"
                    )
                        ChargeReason.NOT_CHARGING
                    else ChargeReason.UNKNOWN
                )
            if (!snapshot.root) return ChargeAssessment(ChargeReason.BASIC_ONLY)

            val results = snapshot.voteResults()
            val controls =
                snapshot.votes().map { vote ->
                    ControlEvidence(
                        vote,
                        kind(vote.name),
                        vote.value >= 0 &&
                            results.any {
                                it.group == vote.group &&
                                    it.name == vote.name &&
                                    it.value == vote.value
                            },
                    )
                }
            val selected = controls.filter { it.selected && it.vote.group in controlGroups }
            val step = stepEvidence(snapshot, properties, results)
            val reason =
                when {
                    selected.any { it.kind in thermalKinds } -> ChargeReason.THERMAL
                    selected.any { it.kind == ControlKind.BATTERY_PROTECTION } ->
                        ChargeReason.PROTECTION
                    selected.any {
                        it.kind in
                            setOf(
                                ControlKind.CHARGE_LEVEL,
                                ControlKind.USAGE_POLICY,
                                ControlKind.INPUT_ADJUSTMENT,
                                ControlKind.USB_LIMIT,
                            )
                    } -> ChargeReason.OTHER_CONTROL
                    snapshot.number("direct_charging_status") == 1.0 &&
                        snapshot.text("direct_charging_chg_status") == "CV_MODE" ->
                        ChargeReason.CONSTANT_VOLTAGE
                    step?.matchesCurrentLimit == true && step.reduced ->
                        if (step.last) ChargeReason.LATE_STEP else ChargeReason.STEP
                    selected.any { it.kind == ControlKind.STEP } -> ChargeReason.STEP
                    else -> ChargeReason.UNKNOWN
                }
            return ChargeAssessment(reason, controls, step, results.isNotEmpty())
        }

        private fun stepEvidence(
            snapshot: Snapshot,
            properties: List<Property>,
            results: List<VoteResult>,
        ): StepEvidence? {
            if (snapshot.number("direct_charging_status") != 1.0) return null
            val index = snapshot.text("direct_charging_step")?.toIntOrNull() ?: return null
            val count =
                properties
                    .singleOrNull { it.key == "battery,dc_step_chg_step" }
                    ?.numbers()
                    ?.singleOrNull()
                    ?.takeIf { it in 1..32 }
                    ?.toInt() ?: return null
            val table =
                properties.singleOrNull { it.key == "battery,dc_step_chg_val_iout" }?.numbers()
                    ?: return null
            if (table.size != count || index !in table.indices || table.any { it !in 1..100_000 })
                return null
            val fcc = results.singleOrNull { it.group == "FCC" }
            // A cable vote contains the programmed step on this firmware, but is not an
            // explicit causal report. Retain that distinction instead of claiming certainty.
            val allowedWinner = fcc?.name in setOf("VOTER_CABLE", "VOTER_DC_STEP_CHARGE")
            val consistentVote =
                snapshot.votes().any {
                    it.group == "FCC" && it.name == fcc?.name && it.value == fcc.value
                }
            return StepEvidence(
                index,
                count,
                table[index],
                table.max(),
                allowedWinner && consistentVote && fcc?.value == table[index],
                fcc?.name == "VOTER_CABLE",
            )
        }

        private fun kind(name: String): ControlKind =
            when (name) {
                "VOTER_LRP_TEMP",
                "VOTER_LRP_ADAPTIVE" -> ControlKind.LRP
                "VOTER_CHG_TEMP" -> ControlKind.CHARGER_TEMPERATURE
                "VOTER_MIX_LIMIT" -> ControlKind.MIXED_TEMPERATURE
                "VOTER_SWELLING" -> ControlKind.BATTERY_PROTECTION
                "VOTER_FULL_CAPACITY",
                "VOTER_FULL_CHARGE" -> ControlKind.CHARGE_LEVEL
                "VOTER_SIOP",
                "VOTER_USER",
                "VOTER_SLATE",
                "VOTER_SMART_SLATE",
                "VOTER_STORE_MODE",
                "VOTER_SLEEP_MODE" -> ControlKind.USAGE_POLICY
                "VOTER_AICL" -> ControlKind.INPUT_ADJUSTMENT
                "VOTER_USB_100MA",
                "VOTER_USB_FAC_100MA" -> ControlKind.USB_LIMIT
                "VOTER_DC_STEP_CHARGE",
                "VOTER_STEP_CHARGE",
                "VOTER_STEP",
                "VOTER_WPC_STEP_CHARGE",
                "VOTER_WPC_DC_STEP_CHARGE" -> ControlKind.STEP
                "VOTER_CABLE" -> ControlKind.CABLE
                "VOTER_AGING_STEP" -> ControlKind.AGING
                "VOTER_DC_MODE" -> ControlKind.DIRECT_MODE
                else -> ControlKind.UNKNOWN
            }
    }
}
