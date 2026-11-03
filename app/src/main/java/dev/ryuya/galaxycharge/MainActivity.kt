package dev.ryuya.galaxycharge

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var reader: RootReader? = null
    private lateinit var catalog: Catalog
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var connect: MaterialButton
    private lateinit var pause: MaterialButton
    private var visible = false
    private var paused = false
    private var rootWanted = false
    private var busy = false
    private var epoch = 0
    private var page = 0
    private var requestCatalog = false
    private var catalogAttempted = false
    private var catalogError: String? = null
    private var lastError: String? = null
    private var snapshot = Snapshot(0, emptyMap(), false)
    private val samples = ArrayDeque<Snapshot>()
    private var interval = 5
    private val poll = Runnable { collect() }
    private val export =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
            ->
            if (uri != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(exportJson())
                    } ?: error("No output")
                }
                    .onSuccess { toast(R.string.exported) }
                    .onFailure { toast(R.string.export_failed) }
            }
        }

    override fun onCreate(state: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        catalog = Catalog(this)
        rootWanted = getPreferences(0).getBoolean("root_connected", false)
        interval =
            getPreferences(0).getInt("interval", 5).takeIf { it in listOf(2, 5, 10, 30) } ?: 5
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val safe =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val toolbar =
            MaterialToolbar(this).apply {
                title = getString(R.string.app_name)
                subtitle = getString(R.string.readonly)
            }
        layout.addView(toolbar)
        status =
            label(getString(R.string.basic_only), 12).apply {
                setPadding(dp(20), dp(4), dp(20), dp(4))
            }
        layout.addView(status)
        val actions = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), 0) }
        connect =
            MaterialButton(this).apply {
                setText(R.string.connect)
                setOnClickListener {
                    rootWanted = true
                    lastError = null
                    paused = false
                    scheduleNow()
                }
            }
        pause =
            MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
                .apply {
                    setText(R.string.pause)
                    setOnClickListener {
                        paused = !paused
                        if (paused) {
                            epoch++
                            busy = false
                            reader?.close()
                            reader = null
                            main.removeCallbacks(poll)
                        } else scheduleNow()
                        updateStatus()
                    }
                }
        actions.addView(connect, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(pause, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        layout.addView(actions)
        scroll = ScrollView(this).apply { isFillViewport = true }
        body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(24))
            }
        scroll.addView(body)
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = BottomNavigationView(this)
        nav.labelVisibilityMode =
            com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
        val labels =
            intArrayOf(
                R.string.overview,
                R.string.limits,
                R.string.settings,
                R.string.history,
                R.string.info,
            )
        val icons =
            intArrayOf(
                R.drawable.ic_charge,
                R.drawable.ic_limits,
                R.drawable.ic_table,
                R.drawable.ic_history,
                R.drawable.ic_info,
            )
        for (i in labels.indices) nav.menu.add(0, i + 1, i, labels[i]).setIcon(icons[i])
        nav.setOnItemSelectedListener {
            page = it.itemId - 1
            render(false)
            true
        }
        layout.addView(nav)
        setContentView(layout)
        val dark =
            resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, layout).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        render(false)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        worker = Executors.newSingleThreadExecutor()
        reader = RootReader()
        scheduleNow()
    }

    override fun onStop() {
        visible = false
        epoch++
        busy = false
        main.removeCallbacks(poll)
        reader?.close()
        reader = null
        worker?.shutdownNow()
        worker = null
        super.onStop()
    }

    private fun scheduleNow() {
        main.removeCallbacks(poll)
        if (visible && !paused) main.post(poll)
    }

    private fun collect() {
        if (!visible || paused || busy) return
        busy = true
        val generation = epoch
        val useRoot = rootWanted
        val readCatalog =
            useRoot && ((!catalogAttempted && catalog.properties.isEmpty()) || requestCatalog)
        if (readCatalog) catalogAttempted = true
        val source = reader ?: RootReader().also { reader = it }
        worker?.execute {
            var properties: List<Property>? = null
            var catalogFailed = false
            val result = runCatching {
                if (!useRoot) basic()
                else {
                    val s = source.snapshot()
                    if (readCatalog)
                        runCatching { source.catalog().also { require(it.isNotEmpty()) } }
                            .onSuccess { properties = it }
                            .onFailure { catalogFailed = true }
                    s
                }
            }
            main.post {
                if (!visible || paused || epoch != generation) return@post
                busy = false
                result
                    .onSuccess { s ->
                        snapshot = s
                        if (properties != null) {
                            runCatching { catalog.replace(properties!!) }
                                .onSuccess { catalogError = null }
                                .onFailure { catalogError = getString(R.string.catalog_error) }
                        }
                        if (readCatalog) requestCatalog = false
                        if (catalogFailed) catalogError = getString(R.string.catalog_error)
                        if (s.root) {
                            getPreferences(0).edit().putBoolean("root_connected", true).apply()
                            lastError = null
                        }
                        samples.addLast(s)
                        while (samples.size > 360) samples.removeFirst()
                    }
                    .onFailure {
                        rootWanted = false
                        getPreferences(0).edit().putBoolean("root_connected", false).apply()
                        lastError = getString(R.string.read_error)
                        snapshot = basic().copy(error = lastError)
                    }
                updateStatus()
                if (page != 2) render(true) else if (properties != null) render(true)
                val delay =
                    if (snapshot.text("status") == "Charging") interval else maxOf(interval, 10)
                main.postDelayed(poll, delay * 1000L)
            }
        }
    }

    private fun basic(): Snapshot {
        val bm = getSystemService(BatteryManager::class.java)
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val map = linkedMapOf<String, String>()
        intent?.let {
            map["capacity"] = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toString()
            map["temp"] = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).toString()
            val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (voltage >= 0) map["voltage_now"] = (voltage * 1000L).toString()
            map["status"] =
                when (it.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                    else -> "Unknown"
                }
        }
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Int.MIN_VALUE) map["current_now"] = current.toString()
        return Snapshot(System.currentTimeMillis(), map, false)
    }

    private fun updateStatus() {
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(snapshot.time))
        status.text =
            (if (paused) getString(R.string.paused)
            else if (snapshot.root) getString(R.string.root_connected)
            else getString(R.string.basic_only)) + " · " + time
        connect.visibility = if (snapshot.root && lastError == null) View.GONE else View.VISIBLE
        connect.setText(if (lastError != null) R.string.retry else R.string.connect)
        pause.setText(if (paused) R.string.resume else R.string.pause)
    }

    private fun render(keepScroll: Boolean) {
        val y = if (keepScroll) scroll.scrollY else 0
        body.removeAllViews()
        when (page) {
            0 -> overview()
            1 -> limits()
            2 -> settings()
            3 -> history()
            else -> info()
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun overview() {
        if (lastError != null) note(lastError!!)
        if (!snapshot.root) note(getString(R.string.root_hint))
        card {
            addView(label(getString(R.string.battery_power), 14))
            addView(label(snapshot.batteryWatts()?.let { "%.2f W".format(it) } ?: "— W", 42, true))
            addView(label(getString(R.string.power_note), 12))
        }
        metrics(
            listOf(
                R.string.battery_level to number("capacity", 1.0, "%"),
                R.string.voltage to number("voltage_now", 1e6, "V"),
                R.string.battery_current to number("current_now", 1e6, "A"),
                R.string.lrp_temp to temperature("lrp"),
                R.string.battery_temp to temperature("temp"),
                R.string.usb_temp to temperature("usb_temp"),
            )
        )
        card {
            pair(this, R.string.charging_state, snapshot.text("status"))
            pair(this, R.string.charging_method, snapshot.text("charging_type"))
            pair(this, R.string.direct_state, snapshot.text("direct_charging_chg_status"))
            pair(
                this,
                R.string.direct_step,
                snapshot.text("direct_charging_step")?.let {
                    if (it == "-1") getString(R.string.step_unused) else it
                },
            )
        }
        card {
            addView(label(getString(R.string.current_limits), 17, true))
            val votes = snapshot.voteResults().filter { it.value >= 0 }
            pair(
                this,
                R.string.input_limit,
                votes.firstOrNull { it.group == "ICL" }?.let { "${it.value} mA · ${it.name}" },
            )
            pair(
                this,
                R.string.battery_limit,
                votes.firstOrNull { it.group == "FCC" }?.let { "${it.value} mA · ${it.name}" },
            )
        }
    }

    private fun limits() {
        section(R.string.votes_title)
        note(getString(R.string.votes_note))
        val votes = snapshot.votes()
        if (votes.isEmpty())
            note(getString(if (snapshot.root) R.string.no_votes else R.string.root_hint))
        snapshot.voteResults().forEach { result ->
            val group = result.group
            val entries = votes.filter { it.group == group }
            card {
                addView(label(group, 18, true))
                val unit =
                    when (group) {
                        "FCC",
                        "ICL",
                        "TOPOFF" -> "mA"
                        "FV",
                        "DCFV",
                        "IV",
                        "MAX_APDO_VOLT" -> "mV"
                        else -> ""
                    }
                addView(label("${result.name} · ${result.value} $unit", 16, true))
                entries.forEach { v ->
                    addView(
                        label(
                            (if (v.winner) getString(R.string.selected) + " · " else "") +
                                "${v.name}\n${v.value} $unit",
                            14,
                        )
                    )
                }
            }
        }
        section(R.string.reference_tables)
        note(getString(R.string.reference_note))
        catalog.properties
            .filter { it.key.contains("temp_table_LRP") }
            .forEach { p ->
                if (p.numbers()?.size !in listOf(12, 15)) return@forEach
                card {
                    addView(label(p.key.substringAfter("temp_table_"), 17, true))
                    for (screen in listOf(false, true)) {
                        val t = LrpThresholds.from(p, screen) ?: continue
                        addView(
                            label(
                                getString(
                                    R.string.lrp_line,
                                    getString(
                                        if (screen) R.string.screen_on else R.string.screen_off
                                    ),
                                    celsius(t.first),
                                    celsius(t.firstRecovery),
                                    celsius(t.second),
                                    celsius(t.secondRecovery),
                                    t.reset?.let(::celsius) ?: "—",
                                ),
                                14,
                            )
                        )
                    }
                }
            }
        val pairs =
            listOf(
                "chg_high_temp" to "chg_high_temp_recovery",
                "dchg_high_temp" to "dchg_high_temp_recovery",
                "dchg_high_batt_temp" to "dchg_high_batt_temp_recovery",
                "wpc_high_temp" to "wpc_high_temp_recovery",
                "wpc_store_high_temp" to "wpc_store_high_temp_recovery",
                "wpc_store_lcd_on_high_temp" to "wpc_store_lcd_on_high_temp_rec",
                "mix_high_temp" to "mix_high_temp_recovery",
                "mix_v2_lrp_cond" to "mix_v2_lrp_recov",
                "overheatlimit_threshold" to "overheatlimit_recovery",
                "tx_high_threshold" to "tx_high_recovery",
                "tx_low_threshold" to "tx_low_recovery",
            )
        for ((start, recovery) in pairs) {
            val a = catalog.property("battery,$start")?.numbers() ?: continue
            val b = catalog.property("battery,$recovery")?.numbers() ?: continue
            if (a.size != b.size) continue
            card {
                addView(label(start, 16, true))
                a.indices.forEach { i ->
                    addView(
                        label("[$i] " + celsius(a[i] / 10.0) + " / " + celsius(b[i] / 10.0), 14)
                    )
                }
                addView(label(getString(R.string.array_note), 12))
            }
        }
        if (Build.DISPLAY == "CP2A.260605.016.S948QOPU1BZI9" && Build.MODEL == "SM-S948Q")
            note(getString(R.string.hysteresis_note))
        section(R.string.all_temperatures)
        metrics(
            listOf(
                R.string.charger_temp to temperature("chg_temp"),
                R.string.direct_temp to temperature("dchg_temp"),
                R.string.wireless_temp to temperature("batt_wpc_temp"),
            )
        )
        card {
            listOf(
                    "siop_level",
                    "pd_disable",
                    "batt_current_event",
                    "batt_misc_event",
                    "batt_swelling_control",
                    "batt_full_capacity",
                    "fg_full_voltage",
                    "direct_charging_ratio",
                    "direct_charging_iin",
                    "dc_vbus",
                    "time_to_full_now",
                )
                .forEach { key ->
                    addView(
                        label(
                            key + "\n" + (snapshot.text(key) ?: getString(R.string.unavailable)),
                            14,
                        )
                    )
                }
        }
    }

    private fun settings() {
        section(R.string.settings)
        note(getString(R.string.settings_note))
        catalogError?.let(::note)
        if (catalog.properties.isEmpty()) {
            note(getString(R.string.settings_empty))
            return
        }
        note(
            getString(
                R.string.settings_count,
                catalog.properties.size,
                getString(if (catalog.fresh) R.string.live_values else R.string.cached_values),
            )
        )
        note(DateFormat.getDateTimeInstance().format(Date(catalog.captured)))
        val refresh =
            MaterialButton(this).apply {
                setText(R.string.refresh_settings)
                setOnClickListener {
                    requestCatalog = true
                    rootWanted = true
                    scheduleNow()
                }
            }
        body.addView(refresh)
        val box = TextInputLayout(this).apply { hint = getString(R.string.settings_search) }
        val search = TextInputEditText(box.context).apply { isSingleLine = true }
        box.addView(search)
        body.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun populate(query: String) {
            list.removeAllViews()
            val ja = resources.configuration.locales[0].language == "ja"
            val items =
                catalog.properties.filter { p ->
                    val d = catalog.descriptions[p.key]
                    (p.key + " " + p.display() + " " + d?.label + " " + d?.category).contains(
                        query,
                        true,
                    )
                }
            items.forEach { p ->
                val d = catalog.descriptions[p.key]
                val title =
                    if (ja) d?.label ?: p.key else p.key.substringAfter(',').replace('_', ' ')
                val c =
                    MaterialCardView(this).apply {
                        radius = dp(18).toFloat()
                        strokeWidth = dp(1)
                    }
                val inner =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
                inner.addView(label(title, 16, true))
                inner.addView(label(p.key, 11))
                inner.addView(
                    label(
                        if (p.presence) getString(R.string.present_flag) else p.display(),
                        15,
                        true,
                    )
                )
                if (ja && d != null)
                    inner.addView(
                        label(d.unit + if (d.note.isNotEmpty()) "\n" + d.note else "", 12)
                    )
                c.addView(inner)
                c.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(p.key)
                        .setMessage(getString(R.string.raw_bytes) + "\n" + p.hex())
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
                list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            if (items.isEmpty()) list.addView(label(getString(R.string.empty), 14))
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populate(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
        populate("")
    }

    private fun history() {
        section(R.string.history)
        note(getString(R.string.history_note))
        body.addView(
            MaterialButton(this).apply {
                setText(R.string.export)
                setOnClickListener {
                    export.launch("galaxy-charge-${System.currentTimeMillis()}.json")
                }
            }
        )
        if (samples.size < 2) {
            note(getString(R.string.history_empty))
            return
        }
        for (temp in listOf(false, true)) {
            card {
                addView(
                    label(
                        getString(if (temp) R.string.temp_history else R.string.battery_power),
                        17,
                        true,
                    )
                )
                addView(
                    HistoryPlot(this@MainActivity, samples.toList(), temp),
                    LinearLayout.LayoutParams(-1, dp(180)),
                )
                if (temp)
                    addView(
                        label(
                            getString(R.string.battery_temp) + " / " + getString(R.string.lrp_temp),
                            12,
                        )
                    )
            }
        }
        card {
            samples.takeLast(12).reversed().forEach { s ->
                addView(
                    label(
                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(s.time)) +
                            "   " +
                            (s.batteryWatts()?.let { "%.2f W".format(it) } ?: "—") +
                            "   LRP " +
                            (s.temperature("lrp")?.let(::celsius) ?: "—"),
                        13,
                    )
                )
            }
        }
    }

    private fun info() {
        section(R.string.info)
        card {
            addView(label(getString(R.string.device), 17, true))
            addView(
                label(
                    Build.MODEL +
                        " · Android " +
                        Build.VERSION.RELEASE +
                        "\n" +
                        Build.DISPLAY +
                        "\n" +
                        BuildConfig.VERSION_NAME,
                    14,
                )
            )
        }
        card {
            addView(label(getString(R.string.scope), 17, true))
            addView(label(getString(R.string.scope_body), 14))
        }
        card {
            addView(label(getString(R.string.limitations), 17, true))
            addView(label(getString(R.string.limitations_body), 14))
        }
        card {
            addView(label(getString(R.string.polling), 17, true))
            val chips =
                ChipGroup(this@MainActivity).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
            listOf(2, 5, 10, 30).forEach { seconds ->
                chips.addView(
                    Chip(this@MainActivity).apply {
                        text = "${seconds}s"
                        isCheckable = true
                        isChecked = interval == seconds
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) {
                                interval = seconds
                                getPreferences(0).edit().putInt("interval", seconds).apply()
                            }
                        }
                    }
                )
            }
            addView(chips)
            addView(label(getString(R.string.poll_note), 13))
        }
        body.addView(
            MaterialButton(this).apply {
                setText(R.string.licenses)
                setOnClickListener { showLicenses() }
            }
        )
        section(R.string.raw_fields)
        card {
            snapshot.fields
                .filterKeys { it != "voter_status" }
                .forEach { (k, v) -> addView(label("$k\n$v", 12)) }
        }
    }

    private fun showLicenses() {
        val entries =
            JSONArray(assets.open("legal/libraries.json").bufferedReader().use { it.readText() })
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
        val box = TextInputLayout(this).apply { hint = getString(R.string.license_search) }
        val search = TextInputEditText(box.context)
        box.addView(search)
        container.addView(box)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply { addView(list) }
        container.addView(scroller, LinearLayout.LayoutParams(-1, dp(380)))
        fun fill(q: String) {
            list.removeAllViews()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (!e.toString().contains(q, true)) continue
                list.addView(
                    MaterialButton(
                            this,
                            null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle,
                        )
                        .apply {
                            text =
                                e.getString("title") +
                                    " " +
                                    e.optString("version") +
                                    "\n" +
                                    e.getString("license")
                            setOnClickListener {
                                val text =
                                    e.optString("notice") +
                                        "\n\n" +
                                        assets.open(e.getString("asset")).bufferedReader().use {
                                            it.readText()
                                        }
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(e.getString("title"))
                                    .setMessage(text)
                                    .setPositiveButton(R.string.close, null)
                                    .show()
                            }
                        }
                )
            }
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    fill(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
        fill("")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.licenses)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun exportJson(): String {
        fun encode(s: Snapshot) =
            JSONObject()
                .put("time", s.time)
                .put("root", s.root)
                .put("fields", JSONObject(s.fields))
                .put("batteryNetWatts", s.batteryWatts() ?: JSONObject.NULL)
        val history = JSONArray()
        samples.forEach { history.put(encode(it)) }
        val properties = JSONArray()
        catalog.properties.forEach {
            properties.put(JSONObject().put("key", it.key).put("bytesHex", it.hex()))
        }
        return JSONObject()
            .put("schema", 1)
            .put("model", Build.MODEL)
            .put("firmware", Build.FINGERPRINT)
            .put("powerMeaning", "Net battery-side power, not USB input power")
            .put("latest", encode(snapshot))
            .put("history", history)
            .put("settingsCaptured", catalog.captured)
            .put("settings", properties)
            .toString(2)
    }

    private fun section(id: Int) {
        body.addView(label(getString(id), 22, true).apply { setPadding(0, dp(12), 0, dp(8)) })
    }

    private fun note(text: String) {
        body.addView(label(text, 13).apply { setPadding(0, 0, 0, dp(12)) })
    }

    private fun card(block: LinearLayout.() -> Unit) {
        val c =
            MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                strokeWidth = dp(1)
                setStrokeColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOutlineVariant,
                    )
                )
                setCardBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                    )
                )
            }
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
        box.block()
        c.addView(box)
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun metrics(entries: List<Pair<Int, String>>) {
        val columns =
            if (
                resources.configuration.fontScale > 1.3 ||
                    resources.configuration.screenWidthDp < 380
            )
                1
            else 2
        entries.chunked(columns).forEach { group ->
            val row = LinearLayout(this)
            group.forEachIndexed { index, (id, value) ->
                val c =
                    MaterialCardView(this).apply {
                        radius = dp(20).toFloat()
                        strokeWidth = dp(1)
                        setStrokeColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOutlineVariant,
                            )
                        )
                        setCardBackgroundColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainer,
                            )
                        )
                    }
                val box =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
                box.addView(label(getString(id), 12))
                box.addView(label(value, 23, true))
                c.addView(box)
                row.addView(
                    c,
                    LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (index > 0) marginStart = dp(10)
                    },
                )
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun pair(box: LinearLayout, id: Int, value: String?) {
        box.addView(label(getString(id), 12))
        box.addView(label(value ?: getString(R.string.unavailable), 16, true))
    }

    private fun label(text: String, size: Int, bold: Boolean = false): MaterialTextView =
        MaterialTextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
            )
            setPadding(0, dp(3), 0, dp(3))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun number(key: String, divisor: Double, unit: String): String =
        snapshot
            .number(key)
            ?.takeIf { key != "capacity" || it in 0.0..100.0 }
            ?.let {
                if (unit == "%") "%.0f %%".format(it) else "%.2f %s".format(it / divisor, unit)
            } ?: getString(R.string.unavailable)

    private fun temperature(key: String) =
        snapshot.temperature(key)?.let(::celsius) ?: getString(R.string.unavailable)

    private fun celsius(value: Double) = "%.1f ℃".format(value)

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(id: Int) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    }
}
