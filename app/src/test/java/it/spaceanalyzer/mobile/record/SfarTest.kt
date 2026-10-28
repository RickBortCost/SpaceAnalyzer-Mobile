package it.spaceanalyzer.mobile.record

import it.spaceanalyzer.link.v1.Sample
import it.spaceanalyzer.mobile.link.SignalInfo
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SfarTest {
    private fun s(id: Int, t: Long, v: Double, q: Int = 0) = Sample.newBuilder().setSignalId(id).setTimestampUs(t).setValue(v).setQuality(q).build()
    private val signals = listOf(SignalInfo(1, "Temp", "°C"), SignalInfo(2, "=cmd;\"x\"", ""))

    private fun record(vararg samples: Sample): File {
        val f = File.createTempFile("sfar-test", ".sfar").apply { deleteOnExit() }
        SfarWriter(f, "Acq", signals, 123L).use { it.append(samples.toList()) }
        return f
    }

    @Test fun roundTripPreservesHeaderAndEveryField() {
        val f = record(s(1, 1_700_000_000_000_000L, -12.5), s(2, 5, Double.MAX_VALUE, 0x80000000.toInt()))
        val r = SfarReader(f.inputStream())
        assertEquals(SfarHeader("Acq", 123L, signals), r.header)
        assertEquals(SfarRecord(1, 1_700_000_000_000_000L, -12.5, 0), r.next())
        assertEquals(SfarRecord(2, 5, Double.MAX_VALUE, 0x80000000.toInt()), r.next())
        assertNull(r.next())
    }

    @Test fun truncatedLastRecordIsIgnored() {
        val f = record(s(1, 1, 1.0), s(1, 2, 2.0))
        val cut = f.readBytes().dropLast(10).toByteArray()
        val r = SfarReader(cut.inputStream())
        assertEquals(1.0, r.next()!!.value, 0.0)
        assertNull(r.next())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsForeignFiles() { SfarReader("PK\u0003\u0004 not sfar".byteInputStream()) }

    @Test fun csvNeutralizesFormulasAndSeparators() {
        val f = record(s(1, 10, 2.5), s(2, 11, 3.0, 1))
        val out = ByteArrayOutputStream()
        assertEquals(2L, sfarToCsv(f.inputStream(), out))
        val lines = out.toString("UTF-8").trim().lines()
        assertEquals("timestamp_us;signal;unit;value;quality", lines[0])
        assertEquals("10;Temp;°C;2.5;0", lines[1])
        assertEquals("11;\"'=cmd;\"\"x\"\"\";;3.0;1", lines[2])
    }
}
