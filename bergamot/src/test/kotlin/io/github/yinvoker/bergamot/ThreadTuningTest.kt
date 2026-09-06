package io.github.yinvoker.bergamot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThreadTuning.recommend] is pure, so the whole decision surface fits in a
 * JVM test: 6 RAM sizes x 3 workloads x low-RAM on/off x 6 core counts = 216
 * cases, enumerated below.
 */
class ThreadTuningTest {

    private companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024L * MB

        /** The enumeration uses round numbers; the named cases use real MemTotal readings. */
        val RAM_SIZES = listOf(4L * GB, 6L * GB, 8L * GB, 10L * GB, 12L * GB, 16L * GB)
        val WORKLOADS = Workload.entries
        val LOW_RAM = listOf(false, true)
        val BIG_CORES = listOf(1, 2, 3, 4, 6, 8)

        val VALID_TIERS = setOf(1, 2, 4, 6)

        /** Mi 12 MemTotal: a "8 GB" phone. */
        const val MI12_TOTAL_RAM_MB = 7360L
    }

    private fun forEachCase(body: (Long, Workload, Boolean, Int, ThreadTuning.Decision) -> Unit) {
        var cases = 0
        for (ram in RAM_SIZES) for (workload in WORKLOADS) for (lowRam in LOW_RAM) for (cores in BIG_CORES) {
            body(ram, workload, lowRam, cores, ThreadTuning.recommend(ram, lowRam, cores, workload))
            cases++
        }
        assertEquals("enumeration size changed; update the doc comment", 216, cases)
    }

    private fun describe(ram: Long, w: Workload, lowRam: Boolean, cores: Int) =
        "ram=${ram / MB}MB workload=$w lowRam=$lowRam bigCores=$cores"

    @Test
    fun `every combination returns a tier we have a baseline for`() {
        forEachCase { ram, w, lowRam, cores, d ->
            assertTrue("threads=${d.threads} not in $VALID_TIERS for ${describe(ram, w, lowRam, cores)}", d.threads in VALID_TIERS)
        }
    }

    @Test
    fun `single sentence is always one thread`() {
        forEachCase { ram, w, lowRam, cores, d ->
            if (w == Workload.SINGLE) assertEquals(describe(ram, w, lowRam, cores), 1, d.threads)
        }
    }

    @Test
    fun `low ram device is always one thread`() {
        forEachCase { ram, w, lowRam, cores, d ->
            if (lowRam) assertEquals(describe(ram, w, lowRam, cores), 1, d.threads)
        }
    }

    @Test
    fun `fewer than two fast cores is always one thread`() {
        forEachCase { ram, w, lowRam, cores, d ->
            if (cores < 2) assertEquals(describe(ram, w, lowRam, cores), 1, d.threads)
        }
    }

    @Test
    fun `pivot never gets more threads than batch on the same inputs`() {
        forEachCase { ram, w, lowRam, cores, d ->
            if (w == Workload.PIVOT) {
                val batch = ThreadTuning.recommend(ram, lowRam, cores, Workload.BATCH)
                assertTrue("pivot ${d.threads} > batch ${batch.threads} for ${describe(ram, w, lowRam, cores)}", d.threads <= batch.threads)
            }
        }
    }

    @Test
    fun `decision echoes back the inputs it was given`() {
        forEachCase { ram, w, lowRam, cores, d ->
            val where = describe(ram, w, lowRam, cores)
            assertEquals(where, ram, d.totalRamBytes)
            assertEquals(where, lowRam, d.isLowRam)
            assertEquals(where, cores, d.bigCoreCount)
            assertEquals(where, w, d.workload)
            // Replaying the recorded inputs is a fixed point — the property the
            // on-device androidTest leans on.
            assertEquals(where, d, ThreadTuning.recommend(ram, lowRam, cores, w))
        }
    }

    @Test
    fun `more fast cores never lowers the tier`() {
        for (ram in RAM_SIZES) for (workload in WORKLOADS) for (lowRam in LOW_RAM) {
            var previous = 0
            for (cores in BIG_CORES) { // already ascending
                val threads = ThreadTuning.recommend(ram, lowRam, cores, workload).threads
                assertTrue("bigCores=$cores dropped $previous -> $threads for ${describe(ram, workload, lowRam, cores)}", threads >= previous)
                previous = threads
            }
        }
    }

    @Test
    fun `more ram never lowers the tier`() {
        for (workload in WORKLOADS) for (cores in BIG_CORES) {
            var previous = 0
            for (ram in RAM_SIZES) { // ascending
                val t = ThreadTuning.recommend(ram, false, cores, workload).threads
                assertTrue("ram $ram cores $cores $workload: $previous -> $t", t >= previous)
                previous = t
            }
        }
    }

    @Test
    fun `ram ladder by nominal size`() {
        // Mi 12: MemTotal 7360 MB reads as a nominal 8 GB phone -> rung 2.
        val mi12 = MI12_TOTAL_RAM_MB * MB
        assertEquals(2, ThreadTuning.recommend(mi12, false, 4, Workload.BATCH).threads)
        assertEquals(2, ThreadTuning.recommend(mi12, false, 4, Workload.PIVOT).threads)
        assertEquals(1, ThreadTuning.recommend(mi12, false, 4, Workload.SINGLE).threads)
        // Mi 10: 7608 MB -> also 8 GB -> 2.
        assertEquals(2, ThreadTuning.recommend(7608L * MB, false, 4, Workload.BATCH).threads)
        // Under 8 GB nominal: 1 whatever the cores.
        assertEquals(1, ThreadTuning.recommend(6L * GB, false, 8, Workload.BATCH).threads)
        assertEquals(1, ThreadTuning.recommend(5L * GB, false, 8, Workload.BATCH).threads)
        // 9.5 GB reads as 10 -> rung 4; 10 GB exactly -> 4.
        assertEquals(4, ThreadTuning.recommend(9728L * MB, false, 8, Workload.BATCH).threads)
        assertEquals(4, ThreadTuning.recommend(10L * GB, false, 8, Workload.BATCH).threads)
        // 11.2 GB (a "12 GB" phone) -> 6 with six fast cores, 4 with four.
        assertEquals(6, ThreadTuning.recommend(11468L * MB, false, 6, Workload.BATCH).threads)
        assertEquals(4, ThreadTuning.recommend(11468L * MB, false, 4, Workload.BATCH).threads)
    }

    @Test
    fun `pivot needs sixteen gigabytes for six threads`() {
        val twelve = 11468L * MB
        assertEquals(6, ThreadTuning.recommend(twelve, false, 6, Workload.BATCH).threads)
        assertEquals(4, ThreadTuning.recommend(twelve, false, 6, Workload.PIVOT).threads)
        // Mi 14 (15160 MB MemTotal, six fast cores): both 6.
        val sixteen = 15160L * MB
        assertEquals(6, ThreadTuning.recommend(sixteen, false, 6, Workload.BATCH).threads)
        assertEquals(6, ThreadTuning.recommend(sixteen, false, 6, Workload.PIVOT).threads)
        // Six threads need six fast cores; an 8 Gen 1 (four) stays at 4 on any phone.
        assertEquals(4, ThreadTuning.recommend(sixteen, false, 4, Workload.BATCH).threads)
    }

    @Test
    fun `two or three fast cores cap batch at two threads`() {
        for (cores in listOf(2, 3)) {
            assertEquals(2, ThreadTuning.recommend(8L * GB, false, cores, Workload.BATCH).threads)
            assertEquals(2, ThreadTuning.recommend(16L * GB, false, cores, Workload.BATCH).threads)
        }
    }

    @Test
    fun `describe carries the fields a bench record needs`() {
        val text = ThreadTuning.recommend(MI12_TOTAL_RAM_MB * MB, false, 4, Workload.BATCH).describe()
        listOf("threads=2", "workload=BATCH", "bigCores=4", "totalRamMb=7360", "lowRam=false")
            .forEach { assertTrue("`$it` missing from `$text`", text.contains(it)) }
    }
}
