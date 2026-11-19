package de.edgefix.store

import android.content.Context
import de.edgefix.core.CorrectionMap
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gespeicherte Kalibrierungen: je Karte eine .efm-Datei und eine .json mit Metadaten. */
class MapStore(context: Context) {
    private val dir = File(context.filesDir, "maps").apply { mkdirs() }

    data class Entry(val id: String, val created: Long, val label: String)

    fun save(bytes: ByteArray, meta: JSONObject?): String {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        File(dir, "$id.efm").writeBytes(bytes)
        File(dir, "$id.json").writeText((meta ?: JSONObject()).put("created", now).toString())
        return id
    }

    fun load(id: String): CorrectionMap? = runCatching {
        CorrectionMap.parse(File(dir, "$id.efm").readBytes())
    }.getOrNull()

    fun list(): List<Entry> = dir.listFiles { f -> f.name.endsWith(".efm") }.orEmpty()
        .map { f ->
            val id = f.name.removeSuffix(".efm")
            val created = runCatching { JSONObject(File(dir, "$id.json").readText()).optLong("created") }
                .getOrNull()?.takeIf { it > 0 } ?: f.lastModified()
            Entry(id, created, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(created)))
        }
        .sortedByDescending { it.created }

    fun delete(id: String) {
        File(dir, "$id.efm").delete()
        File(dir, "$id.json").delete()
    }
}
