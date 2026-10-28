package it.spaceanalyzer.mobile.ui

import it.spaceanalyzer.mobile.link.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendTest {
    @Test fun ringKeepsNewestInOrder() {
        val r = Ring(3); for (i in 1..5) r.add(i.toLong(), i.toDouble())
        assertEquals(listOf(3.0, 4.0, 5.0), r.snapshot().values.toList())
        assertEquals(listOf(3L, 4L, 5L), r.snapshot().timesUs.toList())
    }
    @Test fun decimationKeepsPeaksAndBoundsPoints() {
        val r = Ring(100_000)
        for (i in 0 until 100_000) r.add(i.toLong(), if (i == 54_321) 1000.0 else 0.0)
        val d = decimate(r.snapshot(), 0, 100_000, 200)
        assertTrue(d.x.size <= 200)
        assertEquals(1000.0, d.max.max(), 0.0)
    }
    @Test fun decimationIgnoresSamplesOutsideWindowAndNaN() {
        val r = Ring(10); r.add(0, 5.0); r.add(50, Double.NaN); r.add(200, 9.0)
        val d = decimate(r.snapshot(), 10, 100, 10)
        assertEquals(0, d.x.size)
    }
}
