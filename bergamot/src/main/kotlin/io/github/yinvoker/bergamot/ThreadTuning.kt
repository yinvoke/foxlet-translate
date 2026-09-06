package io.github.yinvoker.bergamot

import android.app.ActivityManager
import android.content.Context

/**
 * What the caller is about to translate. The tier picker treats these as three
 * different cost/benefit problems, not as a size hint.
 */
enum class Workload {
    /**
     * Interactive, one sentence (or a handful) at a time, latency in the loop.
     * Always 1 thread: at 4 workers a single sentence measured 2.35x *slower*
     * than the blocking path, because the ~25 ms dispatch dominates a batch
     * that cannot be split anyway.
     */
    SINGLE,

    /** Bulk translation of many sentences at once — the only case parallelism pays for. */
    BATCH,

    /** Bulk pivot (e.g. ja->en->zh) with both models resident: two model sets, ~1.7x the RAM. */
    PIVOT,
}

/**
 * Picks a translation thread count from what the device can afford.
 *
 * Two halves, deliberately separated:
 *  - [recommend] is pure. Every input is a parameter, so the whole decision
 *    surface is enumerable in a JVM unit test with no device and no emulator.
 *  - [forDevice] (and [EngineConfig.forDevice]) is the Android half: it reads
 *    ActivityManager + [NativeBridge.fastCoreCount] and hands the numbers to
 *    [recommend].
 *
 * Nothing here runs unless a caller asks for it. `EngineConfig()` is still
 * `threads = 1`, and an explicit `EngineConfig(threads = n)` still wins over
 * everything below.
 *
 * Why RAM is no longer the binding constraint: with the int8 embedding tables
 * (patch 0022) each extra worker replica costs ~85 MB steady-state, so bulk
 * 4-thread pivot went from 22% of an 8 GB phone's RAM to ~10.6%. The
 * constraints that actually bind now are the number of fast cores,
 * single-sentence latency (hence [Workload]), and whatever budget the host
 * app can spare.
 */
object ThreadTuning {

    /** 1 MiB. Budgets, costs and the log/JSON views all use this unit. */
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    /** Tiers the picker may return, best first. 3 and 5 have no baseline. */
    private val TIERS = intArrayOf(6, 4, 2, 1)

    /**
     * Steady-state RSS per thread tier, in MB, **process floor included**.
     *
     * Measured on a Mi 12 (8 Gen 1, MemTotal 7.36 GB) after the D cluster
     * landed, en->zh, with `/proc/self/status` VmRSS — not PSS: lmkd and the
     * Android 17+ memory limiter both weigh RSS, and none of this memory is on
     * the Java heap, so `getMemoryClass()` is the wrong yardstick and is never
     * consulted.
     *
     * [SINGLE] is measured at 1 / 2 / 4 (145 / 230 / 401; process floor ~42,
     * first replica ~103, each further replica +85.3); the 6-thread row is
     * +85 per replica, checked on a Mi 14 (six fast cores, 2026-09-06): it
     * measured 604 MB there with a process floor 20 MB above the Mi 12's.
     *
     * [PIVOT] is measured at 1 / 2 / 4 too (Mi 12 and Mi 10, 2026-09-06,
     * smoke --mem steady-state footprint, runs within 2 MB): 250 / 421 / 758
     * with both models resident, i.e. ~+169 MB per extra thread (one replica
     * of each model). Peak VmHWM sits within 1 MB of steady state at 2 and 4
     * threads. The 6-thread row is +169 per thread, checked on the Mi 14
     * (1115 MB measured, same +20 MB floor).
     *
     * Mi 14 measures ~20 MB higher per cell (bigger process floor) and Mi 10
     * agrees in direction; the table intentionally encodes the cheapest of the
     * three — the budget clamp, not a per-row safety factor, is the margin.
     */
    object SteadyStateRssMb {
        /** One model resident: [Workload.SINGLE] and [Workload.BATCH]. */
        val SINGLE = mapOf(1 to 145L, 2 to 230L, 4 to 401L, 6 to 571L)

        /** Two models resident: [Workload.PIVOT]. */
        val PIVOT = mapOf(1 to 250L, 2 to 421L, 4 to 758L, 6 to 1096L)
    }

    /**
     * Product ladder (2026-09-06): the recommended *ceiling* by nominal RAM.
     *
     *   < 8 GB -> 1,  8-9 GB -> 2,  10-11 GB -> 4,  >= 12 GB -> 6
     *
     * A ceiling, not a lock: an explicit `EngineConfig(threads = n)` is never
     * checked against it. The core cap and the RSS budget can still lower the
     * pick (a "12 GB" phone with four fast cores gets 4; pivot at 6 threads
     * needs ~1.1 GB and only clears the derived budget from 16 GB up).
     *
     * "Nominal" because MemTotal under-reports the marketing size: an 8 GB
     * phone shows 7.2-7.8 GB, a 12 GB one ~11.2, a 16 GB one ~15.3. The size
     * is rounded up to the next even GB before the ladder is applied, so 7.36
     * GB reads as 8 and lands on 2, not on the "< 8" rung.
     */
    private fun ramLadderCeiling(totalRamBytes: Long): Int {
        val nominalGb = ((totalRamBytes + 2L * GB - 1) / (2L * GB)) * 2
        return when {
            nominalGb < 8 -> 1
            nominalGb < 10 -> 2
            nominalGb < 12 -> 4
            else -> 6
        }
    }

    /** Lower clamp on the derived budget: below this even 1 thread is pointless to model. */
    private const val MIN_AUTO_BUDGET_MB = 256L

    /**
     * Upper clamp on the derived budget. 8% of a 16 GB phone is ~1.2 GB, which
     * is what 6-thread pivot (two model sets, ~1.1 GB) needs; the clamp only
     * bites from ~19 GB up.
     */
    private const val MAX_AUTO_BUDGET_MB = 1536L

    /** Share of total RAM a library may plan to hold, percent. */
    private const val TOTAL_RAM_SHARE_PERCENT = 8L

    /**
     * Headroom kept above the system's own low-memory threshold when deriving a
     * budget from `availMem`. Spending down to the threshold is what starts a
     * round of background kills the user feels but no benchmark records.
     */
    private const val AVAILABLE_RAM_HEADROOM_MB = 200L

    /** A tier choice plus every input it was made from, so a log line explains itself. */
    data class Decision(
        /** 1, 2, 4 or 6. Feed straight to `EngineConfig(threads = ...)`. */
        val threads: Int,
        val workload: Workload,
        /** `ActivityManager.MemoryInfo.totalMem`, or whatever the caller modelled. */
        val totalRamBytes: Long,
        /** `ActivityManager.isLowRamDevice` — a standalone veto down to 1 thread. */
        val isLowRam: Boolean,
        /** Cores outside the slowest cluster; see [NativeBridge.fastCoreCount]. */
        val bigCoreCount: Int,
        /** The RSS budget actually used, after host override / clamping. */
        val budgetBytes: Long,
        /**
         * Steady-state RSS [threads] is expected to cost, straight off
         * [SteadyStateRssMb] — the number to reconcile a measured VmRSS
         * against, and the one the budget check used.
         */
        val estimatedRssBytes: Long,
    ) {
        /** One line, log- and JSON-friendly. */
        fun describe(): String =
            "threads=$threads workload=$workload bigCores=$bigCoreCount totalRamMb=${totalRamBytes / MB} " +
                "lowRam=$isLowRam budgetMb=${budgetBytes / MB} estRssMb=${estimatedRssBytes / MB}"

        override fun toString(): String = describe()
    }

    /**
     * Pure tier picker. No Android, no I/O, no clock — same inputs, same answer.
     *
     * The rules, in order:
     *  1. [Workload.SINGLE] is always 1 (dispatch costs more than it saves).
     *  2. A low-RAM device is always 1.
     *  3. Fewer than 2 fast cores is always 1 — extra workers would land on
     *     little cores, which measured 3-4x slower.
     *  4. Otherwise take the largest tier that fits all three of: the RAM
     *     ladder ([ramLadderCeiling]), the core cap (6 fast cores -> 6,
     *     4 -> 4, else 2) and the RSS budget.
     *
     * @param totalRamBytes total device RAM. Note this is `MemTotal`-shaped, so
     *   a "8 GB" phone reports ~7.36 GB — never hardcode the nominal number.
     * @param bigCoreCount cores outside the slowest cluster. Must *not* be
     *   `Runtime.availableProcessors()`, which reports the physical core count
     *   and is blind to both cluster speed and the app's cpuset; that value is
     *   only a last-resort fallback when [NativeBridge.fastCoreCount] returns 0.
     * @param hostBudgetBytes RSS the host app is willing to give the engine.
     *   Used verbatim when present — no clamping — because only the host knows
     *   what the rest of the app already holds. When absent the budget is
     *   derived as [TOTAL_RAM_SHARE_PERCENT]% of [totalRamBytes], clamped to
     *   [[MIN_AUTO_BUDGET_MB], [MAX_AUTO_BUDGET_MB]].
     */
    fun recommend(
        totalRamBytes: Long,
        isLowRam: Boolean,
        bigCoreCount: Int,
        workload: Workload,
        hostBudgetBytes: Long? = null,
    ): Decision {
        val budget = hostBudgetBytes ?: (totalRamBytes * TOTAL_RAM_SHARE_PERCENT / 100)
            .coerceIn(MIN_AUTO_BUDGET_MB * MB, MAX_AUTO_BUDGET_MB * MB)

        val threads = when {
            workload == Workload.SINGLE -> 1
            isLowRam -> 1
            bigCoreCount < 2 -> 1
            else -> {
                val coreCap = if (bigCoreCount >= 6) 6 else if (bigCoreCount >= 4) 4 else 2
                val cap = minOf(coreCap, ramLadderCeiling(totalRamBytes))
                TIERS.firstOrNull { it <= cap && costBytes(workload, it) <= budget } ?: 1
            }
        }

        return Decision(
            threads = threads,
            workload = workload,
            totalRamBytes = totalRamBytes,
            isLowRam = isLowRam,
            bigCoreCount = bigCoreCount,
            budgetBytes = budget,
            estimatedRssBytes = costBytes(workload, threads),
        )
    }

    /** Measured steady-state RSS of [threads] under [workload]. */
    private fun costBytes(workload: Workload, threads: Int): Long {
        val table = if (workload == Workload.PIVOT) SteadyStateRssMb.PIVOT else SteadyStateRssMb.SINGLE
        // Every value the picker asks about is a key of TIERS; the elvis only
        // guards a future tier being added to TIERS but not to the table.
        return (table[threads] ?: table.getValue(1)) * MB
    }

    /**
     * Read the device and pick a tier. Not pure: two calls a second apart can
     * differ, because the budget follows `availMem`.
     *
     * The returned [Decision] carries every input it used, so replaying it
     * through [recommend] with `hostBudgetBytes = decision.budgetBytes`
     * reproduces it exactly.
     */
    fun forDevice(context: Context, workload: Workload, hostBudgetBytes: Long? = null): Decision {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return recommend(
            totalRamBytes = memory.totalMem,
            isLowRam = activityManager.isLowRamDevice,
            bigCoreCount = bigCoreCount(),
            workload = workload,
            hostBudgetBytes = hostBudgetBytes ?: deviceBudgetBytes(memory),
        )
    }

    /**
     * Cores outside the slowest cluster, falling back to the physical core
     * count when the native probe has no opinion (single-cluster or uniform
     * SoCs, unreadable sysfs). `availableProcessors()` overstates on a
     * big.LITTLE phone, which is why it is only ever the fallback.
     */
    fun bigCoreCount(): Int {
        val native = try {
            NativeBridge.fastCoreCount()
        } catch (e: LinkageError) {
            // No libbergamot for this ABI, or it failed to load. Tiering can
            // still answer usefully.
            0
        }
        return if (native > 0) native else Runtime.getRuntime().availableProcessors()
    }

    /**
     * RSS this device can spare right now: the smaller of a static share of
     * total RAM and what is actually free above the system's kill threshold,
     * capped at [MAX_AUTO_BUDGET_MB].
     *
     * Deliberately *not* floored at [MIN_AUTO_BUDGET_MB]: a phone that is
     * genuinely out of memory should get 1 thread, and returning 0 here is how
     * that happens. The floor only applies to the pure function's derived
     * budget, where there is no live `availMem` to consult.
     *
     * `getMemoryClass()` is not used anywhere: it is the Java heap limit and
     * every byte the engine allocates is native.
     */
    fun deviceBudgetBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return deviceBudgetBytes(ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) })
    }

    private fun deviceBudgetBytes(memory: ActivityManager.MemoryInfo): Long {
        val staticShare = memory.totalMem * TOTAL_RAM_SHARE_PERCENT / 100
        val dynamicShare = memory.availMem - memory.threshold - AVAILABLE_RAM_HEADROOM_MB * MB
        return minOf(staticShare, maxOf(dynamicShare, 0L)).coerceAtMost(MAX_AUTO_BUDGET_MB * MB)
    }
}
