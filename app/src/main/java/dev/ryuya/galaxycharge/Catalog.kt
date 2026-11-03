package dev.ryuya.galaxycharge

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class SettingDescription(
    val label: String,
    val category: String,
    val unit: String,
    val note: String,
)

internal class Catalog(private val context: Context) {
    val descriptions: Map<String, SettingDescription> = runCatching {
        val a =
            JSONArray(
                context.assets.open("setting-metadata.json").bufferedReader().use { it.readText() }
            )
        (0 until a.length()).associate { i ->
            val o = a.getJSONObject(i)
            o.getString("key") to
                SettingDescription(
                    o.getString("label"),
                    o.getString("category"),
                    o.getString("unit"),
                    o.getString("note"),
                )
        }
    }
        .getOrDefault(emptyMap())
    var properties: List<Property> = emptyList()
        private set

    var captured: Long = 0L
        private set

    var fresh = false
        private set

    private val cache = AtomicFile(File(context.filesDir, "charging-properties-v1.json"))
    private val generation = Build.FINGERPRINT + ":" + BuildConfig.VERSION_CODE

    init {
        runCatching {
            require(cache.baseFile.length() <= 2_000_000)
            val root = JSONObject(cache.openRead().bufferedReader().use { it.readText() })
            require(root.getString("generation") == generation)
            val a = root.getJSONArray("properties")
            require(a.length() <= 1024)
            properties =
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val raw =
                        "GCM_CATALOG/1\n0\n@gcm:" +
                            o.getString("key") +
                            "\n" +
                            o.getString("hex") +
                            "\nGCM_END\n"
                    PropertyParser.parse(raw).single()
                }
            captured = root.getLong("captured")
        }
            .onFailure { properties = emptyList() }
    }

    fun replace(items: List<Property>) {
        require(items.isNotEmpty() && items.size <= 1024) { "Charging settings unavailable" }
        val time = System.currentTimeMillis()
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("key", it.key).put("hex", it.hex())) }
        val root =
            JSONObject().put("generation", generation).put("captured", time).put("properties", a)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = cache.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let { cache.failWrite(it) }
            // A cache failure must not discard a successful live read.
        }
        properties = items
        captured = time
        fresh = true
    }

    fun property(key: String): Property? = properties.firstOrNull { it.key == key }
}
