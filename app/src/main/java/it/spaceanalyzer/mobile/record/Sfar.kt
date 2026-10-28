package it.spaceanalyzer.mobile.record

import it.spaceanalyzer.link.v1.Sample
import it.spaceanalyzer.mobile.link.SignalInfo
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * SFAR v1 (Space Fieldbus Analyzer Recording), big-endian:
 *   "SFAR" | u8 version | u32 headerLength | header (UTF-8 JSON)
 *   then 24-byte records: u32 signalId | i64 timestampUs | f64 value | u32 quality
 * Records are append-only and self-delimiting, so a file cut by a crash stays readable up to the
 * last complete record.
 */
const val SFAR_VERSION = 1
const val SFAR_RECORD_BYTES = 24
private val MAGIC = byteArrayOf('S'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte())

class SfarWriter(file: File, acquisition: String, signals: List<SignalInfo>, startedAtMs: Long) : AutoCloseable {
    private val raw = FileOutputStream(file)
    private val out = DataOutputStream(BufferedOutputStream(raw, 64 * 1024))
    private var lastSync = System.nanoTime()

    init {
        val header = JSONObject()
            .put("acquisition", acquisition)
            .put("startedAtMs", startedAtMs)
            .put("signals", JSONArray(signals.map { JSONObject().put("id", it.id).put("name", it.name).put("unit", it.unit) }))
            .toString().toByteArray(Charsets.UTF_8)
        out.write(MAGIC); out.writeByte(SFAR_VERSION); out.writeInt(header.size); out.write(header)
        out.flush(); raw.fd.sync()
    }

    fun append(samples: List<Sample>) {
        for (s in samples) {
            out.writeInt(s.signalId); out.writeLong(s.timestampUs); out.writeDouble(s.value); out.writeInt(s.quality)
        }
        // Bound the data lost on a crash or a pulled battery to about one second.
        val now = System.nanoTime()
        if (now - lastSync > 1_000_000_000L) { out.flush(); raw.fd.sync(); lastSync = now }
    }

    override fun close() {
        out.flush(); raw.fd.sync(); out.close()
    }
}

data class SfarHeader(val acquisition: String, val startedAtMs: Long, val signals: List<SignalInfo>)
data class SfarRecord(val signalId: Int, val timestampUs: Long, val value: Double, val quality: Int)

class SfarReader(input: InputStream) {
    private val data = DataInputStream(input.buffered(64 * 1024))
    val header: SfarHeader

    init {
        val magic = ByteArray(4); data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Non è un file SFAR" }
        val version = data.readUnsignedByte()
        require(version == SFAR_VERSION) { "Versione SFAR $version non supportata" }
        val length = data.readInt()
        require(length in 0..(1 shl 20)) { "Intestazione SFAR non valida" }
        val json = JSONObject(String(ByteArray(length).also { data.readFully(it) }, Charsets.UTF_8))
        val arr = json.getJSONArray("signals")
        header = SfarHeader(json.optString("acquisition"), json.optLong("startedAtMs"),
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> SignalInfo(o.getInt("id"), o.getString("name"), o.optString("unit")) } })
    }

    /** Returns null at the end of the file, also when the last record is truncated. */
    fun next(): SfarRecord? = try {
        val buf = ByteArray(SFAR_RECORD_BYTES); data.readFully(buf)
        val b = java.nio.ByteBuffer.wrap(buf)
        SfarRecord(b.int, b.long, b.double, b.int)
    } catch (_: EOFException) { null }
}

/** Streams a recording as CSV: timestamp_us;signal;unit;value;quality. */
fun sfarToCsv(input: InputStream, output: OutputStream): Long {
    val reader = SfarReader(input)
    val names = reader.header.signals.associateBy { it.id }
    val w = output.bufferedWriter(Charsets.UTF_8)
    w.write("timestamp_us;signal;unit;value;quality\n")
    var n = 0L
    while (true) {
        val r = reader.next() ?: break
        val s = names[r.signalId]
        w.write("${r.timestampUs};${csvField(s?.name ?: "id${r.signalId}")};${csvField(s?.unit.orEmpty())};${r.value};${r.quality}\n")
        n++
    }
    w.flush()
    return n
}

// Names come from the PC: neutralize separators, quotes and spreadsheet formula prefixes.
private fun csvField(value: String): String {
    val v = value.replace('\n', ' ').replace('\r', ' ')
    val safe = if (v.isNotEmpty() && v[0] in "=+-@") "'$v" else v
    return if (safe.any { it == ';' || it == '"' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
}
