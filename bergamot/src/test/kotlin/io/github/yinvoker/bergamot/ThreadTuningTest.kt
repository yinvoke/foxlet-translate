package io.github.yinvoker.bergamot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThreadTuning.recommend] is pure, so the whole decision surface fits in a
 * JVM test: 6 RAM sizes x 3 workloads x low-RAM on/off x 6 core counts x 3
 * budgets = 648 cases, enumerated below.
 *
 * These tests pin *behaviour*, not the cost table. When a row of
 * [ThreadTuning.SteadyStateRssMb] is re-measured, the invariants and the
 * monotonicity tests must still pass unchanged; only the handful of
 * explicitly-numbered cases at the bottom may need new expectations.
 */
class ThreadTuningTest {

    private companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024L * MB

        /**
         * Nominal size -> what MemTotal actually reports. A "8 GB" Mi 12 has
         * 7.36 GB; the enumeration uses the round numbers, the named cases use
         * the real one.
         */
        val RAM_SIZES = listOf(4L * GB, 6L * GB, 8L * GB, 10L * GB, 12L * GB, 16L * GB)
        val WORKLOADS = Workload.entries
        val LOW_RAM = listOf(false, true)
        val BIG_CORES = listOf(1, 2, 3, 4, 6, 8)

        /** null = derive from total RAM; then one budget too small for 2 threads and one huge. */
        val BUDGETS = listOf<Long?>(null, 200L * MB, 2L * GB)

        val VALID_TIERS = setOf(1, 2, 4, 6)

        /** Mi 12 MemTotal, the machine the cost table was measured on. */
        const val MI12_TOTAL_RAM_MB = 7360L
    }

    private fun forEachCase(body: (Long, Workload, Boolean, Int, Long?, ThreadTuning.Decision) -> Unit) {
        var cases = 0
        for (ram in RAM_SIZES) {
            for (workload in WORKLOADS) {
                for (lowRam in LOW_RAM) {
                    for (cores in BIG_CORES) {
                        for (budget in BUDGETS) {
                            body(
                                ram, workload, lowRam, cores, budget,
                                ThreadTuning.recommend(ram, lowRam, cores, workload, budget),
                            )
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(
            "enumeration size changed; update the doc comment",
            RAM_SIZES.size * WORKLOADS.size * LOW_RAM.size * BIG_CORES.size * BUDGETS.size,
            cases,
        )
    }

    private fun describe(ram: Long, w: Workload, lowRam: Boolean, cores: Int, budget: Long?) =
        "ram=${ram / MB}MB workload=$w lowRam=$lowRam bigCores=$cores hostBudget=${budget?.div(MB)}"

    @Test
    fun `every combination returns a tier we have a baseline for`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            assertTrue(
                "threads=${d.threads} not in $VALID_TIERS for ${describe(ram, w, lowRam, cores, budget)}",
                d.threads in VALID_TIERS,
            )
        }
    }

    @Test
    fun `single sentence is always one thread`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            if (w == Workload.SINGLE) {
                assertEquals(describe(ram, w, lowRam, cores, budget), 1, d.threads)
            }
        }
    }

    @Test
    fun `low ram device is always one thread`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            if (lowRam) {
                assertEquals(describe(ram, w, lowRam, cores, budget), 1, d.threads)
            }
        }
    }

    @Test
    fun `fewer than two fast cores is always one thread`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            if (cores < 2) {
                assertEquals(describe(ram, w, lowRam, cores, budget), 1, d.threads)
            }
        }
    }

    @Test
    fun `pivot never gets more threads than batch on the same inputs`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            if (w == Workload.PIVOT) {
                val batch = ThreadTuning.recommend(ram, lowRam, cores, Workload.BATCH, budget)
                assertTrue(
                    "pivot got ${d.threads} > batch ${batch.threads} for ${describe(ram, w, lowRam, cores, budget)}",
                    d.threads <= batch.threads,
                )
            }
        }
    }

    @Test
    fun `decision echoes back the inputs it was given`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            val where = describe(ram, w, lowRam, cores, budget)
            assertEquals(where, ram, d.totalRamBytes)
            assertEquals(where, lowRam, d.isLowRam)
            assertEquals(where, cores, d.bigCoreCount)
            assertEquals(where, w, d.workload)
            if (budget != null) assertEquals(where, budget, d.budgetBytes)
            // Replaying with the resolved budget must be a fixed point — this is
            // the property the on-device androidTest leans on.
            assertEquals(where, d, ThreadTuning.recommend(ram, lowRam, cores, w, d.budgetBytes))
        }
    }

    @Test
    fun `estimated rss is the cost table entry for the chosen tier`() {
        forEachCase { ram, w, lowRam, cores, budget, d ->
            val table = if (w == Workload.PIVOT) {
                ThreadTuning.SteadyStateRssMb.PIVOT
            } else {
                ThreadTuning.SteadyStateRssMb.SINGLE
            }
            assertEquals(
                describe(ram, w, lowRam, cores, budget),
                table.getValue(d.threads) * MB,
                d.estimatedRssBytes,
            )
        }
    }

    @Test
    fun `a bigger budget never lowers the tier`() {
        val ascending = listOf(0L, 100L * MB, 200L * MB, 256L * MB, 400L * MB, 530L * MB, 600L * MB, 1L * GB, 4L * GB)
        for (ram in RAM_SIZES) {
            for (workload in WORKLOADS) {
                for (lowRam in LOW_RAM) {
                    for (cores in BIG_CORES) {
                        var previous = 0
                        for (budget in ascending) {
                            val threads = ThreadTuning.recommend(ram, lowRam, cores, workload, budget).threads
                            assertTrue(
                                "budget ${budget / MB}MB dropped $previous -> $threads for " +
                                    describe(ram, workload, lowRam, cores, budget),
                                threads >= previous,
                            )
                            previous = threads
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `more fast cores never lowers the tier`() {
        for (ram in RAM_SIZES) {
            for (workload in WORKLOADS) {
                for (lowRam in LOW_RAM) {
                    for (budget in BUDGETS) {
                        var previous = 0
                        for (cores in BIG_CORES) { // already ascending
                            val threads = ThreadTuning.recommend(ram, lowRam, cores, workload, budget).threads
                            assertTrue(
                                "bigCores=$cores dropped $previous -> $threads for " +
                                    describe(ram, workload, lowRam, cores, budget),
                                threads >= previous,
                            )
                            previous = threads
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `host budget wins over total ram in both directions`() {
        // A tiny budget on the biggest phone beats nothing: back to 1 thread.
        for (workload in listOf(Workload.BATCH, Workload.PIVOT)) {
            assertEquals(1, ThreadTuning.recommend(16L * GB, false, 8, workload, 200L * MB).threads)
        }
        // A generous budget lifts the budget check but never the RAM ladder.
        assertEquals(1, ThreadTuning.recommend(4L * GB, false, 8, Workload.BATCH, 2L * GB).threads)
        assertEquals(2, ThreadTuning.recommend(8L * GB, false, 8, Workload.PIVOT, 2L * GB).threads)
        assertEquals(6, ThreadTuning.recommend(12L * GB, false, 8, Workload.PIVOT, 2L * GB).threads)
        // And it is used verbatim, not clamped into [256MB, 1536MB].
        assertEquals(2L * GB, ThreadTuning.recommend(4L * GB, false, 8, Workload.BATCH, 2L * GB).budgetBytes)
        assertEquals(200L * MB, ThreadTuning.recommend(16L * GB, false, 8, Workload.BATCH, 200L * MB).budgetBytes)
    }

    @Test
    fun `derived budget is eight percent of total ram clamped to 256-1536 MB`() {
        // 4 GB -> 327 MB, inside the clamp.
        assertEquals(4L * GB * 8 / 100, ThreadTuning.recommend(4L * GB, false, 4, Workload.BATCH).budgetBytes)
        // 16 GB -> 1310 MB, still inside; 24 GB -> 1966 MB, clamped down.
        assertEquals(16L * GB * 8 / 100, ThreadTuning.recommend(16L * GB, false, 4, Workload.BATCH).budgetBytes)
        assertEquals(1536L * MB, ThreadTuning.recommend(24L * GB, false, 4, Workload.BATCH).budgetBytes)
        // 1 GB -> 81 MB, clamped up (the floor only exists for this modelled path).
        assertEquals(256L * MB, ThreadTuning.recommend(1L * GB, false, 4, Workload.BATCH).budgetBytes)
    }

    @Test
    fun `ram ladder by nominal size`() {
        // Mi 12: MemTotal 7360 MB reads as a nominal 8 GB phone -> rung 2.
        val mi12 = MI12_TOTAL_RAM_MB * MB
        assertEquals(2, ThreadTuning.recommend(mi12, false, 4, Workload.BATCH).threads)
        assertEquals(2, ThreadTuning.recommend(mi12, false, 4, Workload.PIVOT).threads)
        assertEquals(1, ThreadTuning.recommend(mi12, false, 4, Workload.SINGLE).threads)
        assertEquals(230L * MB, ThreadTuning.recommend(mi12, false, 4, Workload.BATCH).estimatedRssBytes)
        assertEquals(421L * MB, ThreadTuning.recommend(mi12, false, 4, Workload.PIVOT).estimatedRssBytes)
        // Mi 10: 7608 MB -> also 8 GB -> 2.
        assertEquals(2, ThreadTuning.recommend(7608L * MB, false, 4, Workload.BATCH).threads)
        // Under 8 GB nominal: 1 whatever the cores and budget.
        assertEquals(1, ThreadTuning.recommend(6L * GB, false, 8, Workload.BATCH).threads)
        assertEquals(1, ThreadTuning.recommend(5L * GB, false, 8, Workload.BATCH, 2L * GB).threads)
        // 9.5 GB reads as 10 -> rung 4; 10 GB exactly -> 4.
        assertEquals(4, ThreadTuning.recommend(9728L * MB, false, 8, Workload.BATCH).threads)
        assertEquals(4, ThreadTuning.recommend(10L * GB, false, 8, Workload.BATCH).threads)
        // 11.2 GB (a "12 GB" phone) -> 6 with six fast cores, 4 with four.
        assertEquals(6, ThreadTuning.recommend(11468L * MB, false, 6, Workload.BATCH).threads)
        assertEquals(4, ThreadTuning.recommend(11468L * MB, false, 4, Workload.BATCH).threads)
    }

    @Test
    fun `twelve and sixteen gigabytes with six big cores`() {
        // "12 GB" (11.2 GB MemTotal, budget 917 MB): batch 6 (571) fits, pivot 6 (1096) does not -> 4 (758).
        val twelve = 11468L * MB
        assertEquals(6, ThreadTuning.recommend(twelve, false, 6, Workload.BATCH).threads)
        assertEquals(4, ThreadTuning.recommend(twelve, false, 6, Workload.PIVOT).threads)
        // "16 GB" (15.3 GB MemTotal, budget 1253 MB): both 6.
        val sixteen = 15667L * MB
        assertEquals(6, ThreadTuning.recommend(sixteen, false, 6, Workload.BATCH).threads)
        assertEquals(6, ThreadTuning.recommend(sixteen, false, 6, Workload.PIVOT).threads)
        assertEquals(571L * MB, ThreadTuning.recommend(sixteen, false, 6, Workload.BATCH).estimatedRssBytes)
        assertEquals(1096L * MB, ThreadTuning.recommend(sixteen, false, 6, Workload.PIVOT).estimatedRssBytes)
        // Six threads need six fast cores; an 8 Gen 1 (four) stays at 4 on any phone.
        assertEquals(4, ThreadTuning.recommend(sixteen, false, 4, Workload.BATCH).threads)
        // 4 GB (327 MB budget, ladder rung 1): 1 everywhere.
        assertEquals(1, ThreadTuning.recommend(4L * GB, false, 4, Workload.BATCH).threads)
        assertEquals(1, ThreadTuning.recommend(4L * GB, false, 4, Workload.PIVOT).threads)
    }

    @Test
    fun `more ram never lowers the tier`() {
        for (workload in WORKLOADS) for (cores in BIG_CORES) for (budget in BUDGETS) {
            var previous = 0
            for (ram in RAM_SIZES) { // ascending
                val t = ThreadTuning.recommend(ram, false, cores, workload, budget).threads
                assertTrue("ram $ram cores $cores $workload budget $budget: $previous -> $t", t >= previous)
                previous = t
            }
        }
    }

    @Test
    fun `two or three fast cores cap batch at two threads`() {
        for (cores in listOf(2, 3)) {
            assertEquals(2, ThreadTuning.recommend(8L * GB, false, cores, Workload.BATCH).threads)
            assertEquals(2, ThreadTuning.recommend(16L * GB, false, cores, Workload.BATCH).threads)
        }
    }

    @Test
    fun `a budget too small even for one thread still returns one`() {
        val d = ThreadTuning.recommend(8L * GB, false, 8, Workload.BATCH, 0L)
        assertEquals(1, d.threads)
        assertEquals(0L, d.budgetBytes)
    }

    @Test
    fun `describe carries the fields a bench record needs`() {
        val text = ThreadTuning.recommend(MI12_TOTAL_RAM_MB * MB, false, 4, Workload.BATCH).describe()
        listOf("threads=2", "workload=BATCH", "bigCores=4", "totalRamMb=7360", "lowRam=false", "budgetMb=", "estRssMb=")
            .forEach { assertTrue("`$it` missing from `$text`", text.contains(it)) }
    }
}
