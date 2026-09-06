package io.github.yinvoker.bergamot

import android.app.ActivityManager
import android.content.Context

/**
 * What the caller is about to translate. The tier picker treats these as three
 * different problems, not as a size hint.
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
 * Picks a translation thread count from the device's nominal RAM and its fast
 * core count.
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
 * everything below. The pick is a recommended ceiling, not a lock.
 */
object ThreadTuning {

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

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
    ) {
        /** One line, log- and JSON-friendly. */
        fun describe(): String =
            "threads=$threads workload=$workload bigCores=$bigCoreCount totalRamMb=${totalRamBytes / MB} lowRam=$isLowRam"

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
     *  4. Otherwise the smaller of the RAM ladder ([ramLadderCeiling]) and the
     *     core cap: 6 fast cores -> 6, 4 -> 4, else 2.
     *
     * @param totalRamBytes total device RAM. Note this is `MemTotal`-shaped, so
     *   a "8 GB" phone reports ~7.36 GB — never hardcode the nominal number.
     * @param bigCoreCount cores outside the slowest cluster. Must *not* be
     *   `Runtime.availableProcessors()`, which reports the physical core count
     *   and is blind to both cluster speed and the app's cpuset; that value is
     *   only a last-resort fallback when [NativeBridge.fastCoreCount] returns 0.
     */
    fun recommend(totalRamBytes: Long, isLowRam: Boolean, bigCoreCount: Int, workload: Workload): Decision {
        val threads = when {
            workload == Workload.SINGLE -> 1
            isLowRam -> 1
            bigCoreCount < 2 -> 1
            else -> {
                val coreCap = if (bigCoreCount >= 6) 6 else if (bigCoreCount >= 4) 4 else 2
                minOf(coreCap, ramLadderCeiling(totalRamBytes, workload))
            }
        }
        return Decision(threads, workload, totalRamBytes, isLowRam, bigCoreCount)
    }

    /**
     * Product ladder (2026-09-06): the recommended ceiling by nominal RAM.
     *
     *   < 8 GB -> 1,  8-9 GB -> 2,  10-11 GB -> 4,  >= 12 GB -> 6
     *
     * A pivot keeps two model sets resident (~1.1 GB at 6 threads, measured on
     * a Mi 14), so its top rung starts at 16 GB; a "12 GB" pivot stays at 4.
     *
     * "Nominal" because MemTotal under-reports the marketing size: an 8 GB
     * phone shows 7.2-7.8 GB, a 12 GB one ~11.2, a 16 GB one ~15.3. The size
     * is rounded up to the next even GB before the ladder is applied, so 7.36
     * GB reads as 8 and lands on 2, not on the "< 8" rung.
     */
    private fun ramLadderCeiling(totalRamBytes: Long, workload: Workload): Int {
        val nominalGb = ((totalRamBytes + 2L * GB - 1) / (2L * GB)) * 2
        return when {
            nominalGb < 8 -> 1
            nominalGb < 10 -> 2
            nominalGb < 12 -> 4
            workload == Workload.PIVOT && nominalGb < 16 -> 4
            else -> 6
        }
    }

    /** Read the device and pick a tier. */
    fun forDevice(context: Context, workload: Workload): Decision {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return recommend(
            totalRamBytes = memory.totalMem,
            isLowRam = activityManager.isLowRamDevice,
            bigCoreCount = bigCoreCount(),
            workload = workload,
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
}
