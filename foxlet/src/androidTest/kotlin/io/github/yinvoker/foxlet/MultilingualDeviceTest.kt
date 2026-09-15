package io.github.yinvoker.foxlet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with validationRoot; missing provisioned inputs/models then fail rather than skip. */
@RunWith(AndroidJUnit4::class)
class MultilingualDeviceTest {
    @Test
    fun directAndPivotTranslationsAreCompleteAndRepeatable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val relativeRoot = InstrumentationRegistry.getArguments().getString("validationRoot")
        Assume.assumeTrue("Pass validationRoot to run the provisioned multilingual regression", relativeRoot != null)
        val root = File(instrumentation.targetContext.filesDir, requireNotNull(relativeRoot))
        val fixture = JSONObject(File(root, "inputs.json").readText())
        val scenarios = fixture.getJSONArray("scenarios")
        val results = JSONArray()
        val report = File(root, "outputs.json")
        for (workers in listOf(1, 2)) {
            for (i in 0 until scenarios.length()) {
                val scenario = scenarios.getJSONObject(i)
                val name = scenario.getString("direction")
                val input = scenario.getJSONArray("inputs")
                val texts = (0 until input.length()).map { input.getString(it) }
                val firstModel = ModelFiles.fromDirectory(File(root, "models/${scenario.getString("model")}"))
                val secondModel = scenario.optString("pivot").takeIf { it.isNotEmpty() }
                    ?.let { ModelFiles.fromDirectory(File(root, "models/$it")) }
                NativeEngine(EngineOptions(threads = workers, miniBatchWords = 512, cacheSize = 0)).use { engine ->
                    suspend fun translate() = if (secondModel == null) engine.translate(texts, firstModel)
                        else engine.translatePivot(texts, firstModel, secondModel)
                    val start = System.nanoTime()
                    val first = translate()
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    val second = translate()
                    results.put(JSONObject().put("direction", name).put("workers", workers)
                        .put("first_ms", elapsed).put("outputs", JSONArray(first))
                        .put("repeat_equal", first == second))
                    report.writeText(JSONObject().put("results", results).toString(2))
                    assertEquals("$name workers=$workers output count", texts.size, first.size)
                    assertTrue("$name workers=$workers blank output", first.none { it.isBlank() })
                    assertTrue("$name workers=$workers invalid Unicode", first.none { '\uFFFD' in it || '\u0000' in it })
                    assertEquals("$name workers=$workers nondeterministic repeat", first, second)
                    Log.i("foxlet-test", "multilingual direction=$name workers=$workers rows=${texts.size} first_ms=$elapsed repeat_equal=true")
                }
            }
        }
        assertEquals(16, results.length())
    }
}
