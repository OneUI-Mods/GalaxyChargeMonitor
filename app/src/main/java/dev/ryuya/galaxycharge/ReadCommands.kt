package dev.ryuya.galaxycharge

/** Fixed read commands only. No UI value or device output is interpolated into a command. */
internal object ReadCommands {
    val fields =
        listOf(
            "status",
            "health",
            "capacity",
            "temp",
            "lrp",
            "voltage_now",
            "voltage_avg",
            "current_now",
            "current_avg",
            "charge_counter",
            "charge_full",
            "charging_type",
            "chg_type",
            "chg_info",
            "hv_charger_status",
            "pd_disable",
            "siop_level",
            "siop_activated",
            "direct_charging_status",
            "direct_charging_chg_status",
            "direct_charging_iin",
            "direct_charging_step",
            "direct_charging_ratio",
            "dc_vbus",
            "dc_op_mode",
            "chg_temp",
            "dchg_temp",
            "usb_temp",
            "batt_wpc_temp",
            "sub_chg_temp",
            "sub_bat_temp",
            "blkt_temp",
            "batt_current_event",
            "batt_misc_event",
            "batt_full_capacity",
            "batt_slate_mode",
            "batt_swelling_control",
            "time_to_full_now",
            "voter_status",
            "charger_ic_name",
            "fg_full_voltage",
            "fg_cycle",
            "fg_asoc",
            "battery_cycle",
            "pass_through",
            "store_mode",
            "check_ps_ready",
        )
    val snapshot: String = buildString {
        append("printf 'GCM_SNAPSHOT/1\\n'; id -u; printf '@gcm:clock\\n'; date +%s; ")
        for (name in fields) {
            require(name.matches(Regex("[a-z0-9_]+")))
            append("printf '@gcm:$name\\n'; ")
            append("if [ -r /sys/class/power_supply/battery/$name ]; then ")
            append("head -c 32768 /sys/class/power_supply/battery/$name 2>/dev/null; ")
            append("else printf 'UNAVAILABLE'; fi; printf '\\n'; ")
        }
        append("printf 'GCM_END\\n'")
    }
    val catalog: String =
        """
        printf 'GCM_CATALOG/1\n'; id -u;
        for p in /sys/firmware/devicetree/base/samsung_mobile_device/battery/*; do
            [ -f "${'$'}p" ] || continue;
            name=${'$'}{p##*/};
            case "${'$'}name" in *[!a-zA-Z0-9_,.-]*) continue;; esac;
            printf '@gcm:%s\n' "${'$'}name";
            head -c 16384 "${'$'}p" 2>/dev/null | od -An -v -tx1;
            printf '\n';
        done;
        printf 'GCM_END\n'
        """
            .trimIndent()
}
