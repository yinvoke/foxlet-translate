package io.github.yinvoker.foxlet.demo

import android.app.KeyguardManager
import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.os.Debug
import android.os.PowerManager
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yinvoker.foxlet.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseBenchmarkTest {
    @Test fun benchmark(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val args = InstrumentationRegistry.getArguments()
        val direction = requireNotNull(args.getString("direction"))
        val workers = requireNotNull(args.getString("workers")).toInt()
        ins.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/io.github.yinvoker.foxlet.demo.MainActivity").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        var resumed: Activity? = null
        for (attempt in 0 until 50) {
            ins.runOnMainSync { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
            if (resumed != null) break
            Thread.sleep(100)
        }
        val activity = requireNotNull(resumed) { "Benchmark activity did not resume" }
        ins.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        ins.waitForIdleSync()
        fun checkForeground() {
            assertTrue("Screen must be interactive", context.getSystemService(PowerManager::class.java).isInteractive)
            assertFalse("Unlock the phone for benchmarking", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            ins.runOnMainSync { assertTrue("Benchmark activity must have focus", activity.hasWindowFocus()) }
        }
        for (attempt in 0 until 60) {
            var focused = false
            ins.runOnMainSync { focused = activity.hasWindowFocus() }
            if (focused) break
            Thread.sleep(50)
        }
        checkForeground()
        val root = File(context.filesDir, "offline-translation-validation")
        val fixture = JSONObject(ins.context.assets.open("size-validation/inputs.json").bufferedReader().use { it.readText() })
        val scenarios = fixture.getJSONArray("scenarios")
        val scenario = (0 until scenarios.length()).map { scenarios.getJSONObject(it) }.single { it.getString("direction") == direction }
        val inputs = scenario.getJSONArray("inputs")
        val texts = (0 until scenario.getInt("flores_single_count")).map { inputs.getString(it) }
        val (source, target) = direction.split('-')
        fun lang(s: String) = if (s == "zh") "zh-Hans" else s
        fun model(name: String, src: String, trg: String) = ExternalModel(LanguagePair(lang(src), lang(trg)),
            ModelFiles.fromDirectory(File(root, "models/$name")))
        val pivot = scenario.optString("pivot").takeIf { it.isNotEmpty() }
        val first = model(scenario.getString("model"), source, if (pivot == null) target else "en")
        val second = pivot?.let { model(it, "en", target) }
        val samples = JSONArray()
        var client: Foxlet? = null
        var reference: List<String>? = null
        try {
            repeat(3) { iteration ->
                val running = AtomicBoolean(true)
                val peak = AtomicLong(Debug.getPss().toLong())
                val sampler = thread(name = "pss-sampler") {
                    while (running.get()) {
                        peak.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
                        Thread.sleep(100)
                    }
                }
                val start = System.nanoTime()
                val output: List<String>
                val elapsed: Double
                try {
                    val engine = client ?: Foxlet.create(context, FoxletConfig(
                        models = ModelsConfig(directory = File(root, "managed")),
                        translation = TranslationConfig(threading = Threading.Fixed(workers), miniBatchWords = 512, cacheSize = 0)
                    )).also { client = it }
                    output = if (second == null) engine.translator.translate(texts, first)
                        else engine.translator.translatePivot(texts, first, second)
                    elapsed = (System.nanoTime() - start) / 1e6
                    peak.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
                } finally {
                    running.set(false)
                    sampler.join()
                }
                if (reference == null) reference = output else assertEquals(reference, output)
                val hash = MessageDigest.getInstance("SHA-256").digest(output.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
                samples.put(JSONObject().put("iteration", iteration).put("elapsed_ms", elapsed)
                    .put("peak_pss_kib", peak.get()).put("output_sha256", hash))
            }
            checkForeground()
            val result = JSONObject().put("direction", direction).put("workers", workers).put("count", texts.size)
                .put("samples", samples).put("foreground_before_after_passed", true)
            File(requireNotNull(context.getExternalFilesDir(null)), "release-benchmark.json").writeText(result.toString(2))
        } finally {
            client?.shutdown()
            ins.runOnMainSync { activity.finish() }
        }
    }
}
