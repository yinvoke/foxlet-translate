package io.github.yinvoker.foxlet

import android.app.ActivityManager
import android.content.Context

/**
 * What the caller is about to translate. The tier picker treats these as three
 * different problems, not as a size hint.
 */
enum class Workload {
    /**
     * Interactive translation of one or a few sentences. Uses one thread to avoid
     * worker dispatch overhead.
     */
    SINGLE,

    /** Bulk translation of many sentences at once — the only case parallelism pays for. */
    BATCH,

    /** Bulk pivot (e.g. ja->en->zh) with both model sets resident. */
    PIVOT,
}

/**
 * Recommends a thread ceiling from RAM and fast-core count. [recommend] is pure;
 * [forDevice] reads the Android device. Explicit [EngineConfig] values take precedence.
 */
object ThreadTuning {

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    /** Recommended thread count and the device inputs used to select it. */
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
     * Choose 1 thread for single input, low-RAM devices or fewer than 2 fast cores.
     * Otherwise cap the RAM-based tier at the fast-core count, using 2, 4 or 6.
     *
     * @param totalRamBytes reported device RAM, before nominal-size rounding.
     * @param bigCoreCount cores outside the slowest cluster; use [bigCoreCount].
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
     * Round reported RAM up to the next even GiB to approximate nominal capacity.
     * Ceilings: <8 GiB → 1, <10 → 2, <12 → 4, otherwise 6.
     * Pivot workloads keep two model sets resident and require 16 GiB for 6 threads.
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
            // No libfoxlet for this ABI, or it failed to load. Tiering can
            // still answer usefully.
            0
        }
        return if (native > 0) native else Runtime.getRuntime().availableProcessors()
    }
}
