package io.github.yinvoker.foxlet.bench

import android.content.Context
import android.os.Build
import android.os.Debug
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.yinvoker.foxlet.BergamotEngine
import io.github.yinvoker.foxlet.EngineConfig
import io.github.yinvoker.foxlet.ModelFiles
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * android-app-v1: one engine/direction per fresh process, one first pass and
 * two warm passes over the same 200 inputs. Model downloads run separately.
 * The host force-stops between runs and records APK/input/device fingerprints.
 * This app-level protocol does not change the native eight-scenario suite.
 */
class IsolatedBenchRunner(
    private val context: Context,
    private val backend: String,
    private val direction: String,
    private val threads: Int,
    private val runId: String,
    private val prepare: Boolean,
    private val log: (String) -> Unit,
) {
    init {
        require(backend in setOf("mlkit", "bergamot"))
        require(direction in setOf("enzh", "jazh"))
        require(threads in setOf(1, 2, 4))
        require(backend != "mlkit" || threads == 1) { "ML Kit does not expose worker control" }
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")))
        require(!prepare || backend == "mlkit")
    }

    private fun newTranslator(): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(if (direction == "enzh") TranslateLanguage.ENGLISH else TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build(),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    suspend fun run(): JSONObject {
        val output = File(context.filesDir, "isolated_$runId.json")
        check(output.createNewFile()) { "Run ID already exists: $runId" }
        val asset = if (direction == "enzh") "eng.txt" else "jpn.txt"
        val inputBytes = context.assets.open("bench/$asset").use { it.readBytes() }
        val inputs = inputBytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        require(inputs.size == 200)
        val passes = JSONArray()
        val report = JSONObject()
            .put("suite_id", "android-app-v1")
            .put("run_id", runId)
            .put("mode", if (prepare) "prepare" else "measure")
            .put("engine", backend)
            .put("direction", direction)
            .put("threads", if (backend == "mlkit") JSONObject.NULL else threads)
            .put("input_count", inputs.size)
            .put("corpus_sha256", sha256(inputBytes))
            .put("timestamp_ms", System.currentTimeMillis())
            .put("device_model", Build.MODEL)
            .put("device_fingerprint", Build.FINGERPRINT)
            .put("status", "running")
            .put("protocol", JSONObject()
                .put("passes", 3)
                .put("pss_interval_ms", 250)
                .put("first", "engine creation + model loading + full first translation; no download or explicit prewarm")
                .put("warm", "full repeated translation with the same engine/model resident")
                .put("input_mode", if (backend == "mlkit") "SDK per input" else "AAR batch")
                .put("bergamot_cache_size", 0)
                .put("mlkit_cache_control", "not exposed by SDK")
                .put("os_file_cache", "not cleared"))
            .put("passes", passes)
        fun save() = output.writeText(report.toString(2) + "\n")
        save()
        var translator: Translator? = null
        var engine: BergamotEngine? = null
        try {
            if (prepare) {
                translator = newTranslator()
                Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 600, TimeUnit.SECONDS)
                report.put("status", "complete")
                log("models ready; restart process before measuring")
            } else {
                // File discovery is outside timing; ModelFiles parsing/loading
                // remains inside the first timed AAR call below.
                val models = if (backend == "bergamot") {
                    listOfNotNull(context.getExternalFilesDir(null)?.let { File(it, "models") }, File(context.filesDir, "models"))
                        .firstOrNull { File(it, "enzh").listFiles()?.any { f -> f.name.endsWith(".bin") } == true }
                        ?: error("Bergamot models missing")
                } else null
                val idle = List(6) { Debug.getPss().also { Thread.sleep(250) } }.sorted()
                report.put("baseline_pss_mib", (idle[2] + idle[3]) / 2048.0)
                repeat(3) { pass ->
                    log("$backend $direction ${threads}t pass $pass")
                    val sampler = MetricsSampler().also { it.start() }
                    val sample = JSONObject().put("pass", pass)
                    try {
                        val start = System.nanoTime()
                        val translated = if (backend == "mlkit") {
                            val client = translator ?: newTranslator().also { translator = it }
                            inputs.map { Tasks.await(client.translate(it), 120, TimeUnit.SECONDS) }
                        } else {
                            val client = engine ?: BergamotEngine(EngineConfig(
                                threads = threads, cacheSize = 0, idleUnloadMillis = -1,
                            )).also { engine = it }
                            val enzh = ModelFiles.fromDirectory(File(models!!, "enzh"))
                            if (direction == "enzh") client.translate(inputs, enzh)
                            else client.translatePivot(inputs, ModelFiles.fromDirectory(File(models, "jaen")), enzh)
                        }
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                        require(translated.size == inputs.size)
                        sample.put("elapsed_ms", elapsedMs)
                            .put("inputs_per_second", inputs.size * 1000.0 / elapsedMs)
                            .put("output_sha256", sha256((translated.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)))
                            .put("outputs", JSONArray(translated))
                    } catch (error: Exception) {
                        sample.put("error", error.toString())
                        throw error
                    } finally {
                        sample.put("metrics", sampler.stopAndReport())
                        passes.put(sample)
                        save()
                    }
                }
                report.put("status", "complete")
            }
        } catch (error: Exception) {
            report.put("status", "failed").put("error", error.toString())
            log("FAILED: $error")
        } finally {
            try {
                translator?.close()
                engine?.close()
            } catch (error: Exception) {
                report.put("status", "failed").put("cleanup_error", error.toString())
            }
            save()
        }
        log("${report.getString("status")} -> ${output.name}")
        return report
    }
}
