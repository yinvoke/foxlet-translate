package io.github.yinvoker.foxlet.demo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yinvoker.foxlet.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Final-AAR/R8 regression; opt in by attaching local assets and passing validationAsset. */
@RunWith(AndroidJUnit4::class)
class OfflineTranslationTest {
    @Test fun multilingualDirectAndPivot(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assetRoot = InstrumentationRegistry.getArguments().getString("validationAsset")
        Assume.assumeTrue("Pass validationAsset with local validation assets", assetRoot != null)
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val root = File(context.filesDir, "offline-translation-validation").apply { mkdirs() }
        val fixture = JSONObject(assets.open("$assetRoot/inputs.json").bufferedReader().use { it.readText() })
        val scenarios = fixture.getJSONArray("scenarios")
        val names = mutableSetOf<String>()
        for (i in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(i)
            names += scenario.getString("model")
            scenario.optString("pivot").takeIf { it.isNotEmpty() }?.let { names += it }
        }
        for (name in names) {
            val directory = File(root, "models/$name").apply { mkdirs() }
            val path = "$assetRoot/models/$name"
            for (file in requireNotNull(assets.list(path))) {
                assets.open("$path/$file").use { input -> File(directory, file).outputStream().use { input.copyTo(it) } }
            }
            Log.i("foxlet-offline", "prepared local model $name")
        }
        fun language(value: String) = if (value == "zh") "zh-Hans" else value
        fun model(name: String, source: String, target: String) = ExternalModel(
            LanguagePair(language(source), language(target)), ModelFiles.fromDirectory(File(root, "models/$name")),
        )
        val results = JSONArray()
        val report = File(requireNotNull(context.getExternalFilesDir(null)), "offline-translation-outputs.json")
        for (workers in listOf(1, 2)) {
            for (i in 0 until scenarios.length()) {
                val scenario = scenarios.getJSONObject(i)
                val direction = scenario.getString("direction")
                val (source, target) = direction.split('-')
                val input = scenario.getJSONArray("inputs")
                val texts = (0 until input.length()).map { input.getString(it) }
                val pivotName = scenario.optString("pivot").takeIf { it.isNotEmpty() }
                val first = model(scenario.getString("model"), source, if (pivotName == null) target else "en")
                val second = pivotName?.let { model(it, "en", target) }
                val client = Foxlet.create(context, FoxletConfig(
                    models = ModelsConfig(directory = File(root, "managed")),
                    translation = TranslationConfig(threading = Threading.Fixed(workers), miniBatchWords = 512, cacheSize = 0),
                ))
                try {
                    suspend fun translate() = if (second == null) client.translator.translate(texts, first)
                        else client.translator.translatePivot(texts, first, second)
                    val output = translate()
                    val again = translate()
                    results.put(JSONObject().put("direction", direction).put("workers", workers)
                        .put("outputs", JSONArray(output)).put("repeat_equal", output == again))
                    report.writeText(JSONObject().put("results", results).toString(2))
                    assertEquals("$direction output count", texts.size, output.size)
                    assertTrue("$direction blank output", output.none { it.isBlank() })
                    assertTrue("$direction invalid Unicode", output.none { '\uFFFD' in it || '\u0000' in it })
                    assertEquals("$direction workers=$workers repeat", output, again)
                    assertTrue(client.translator.unloadModels().allReleased)
                    assertEquals(0, client.translator.getState().loadedModelCount)
                    if (workers == 1 && i == 0) {
                        assertTrue(client.translator.translate(emptyList(), first).isEmpty())
                        assertTrue(client.translator.translate("Hello, world.", first).isNotBlank())
                        val html = client.translator.translate("<p>Hello, world.</p>", first, TextFormat.Html)
                        assertTrue(html.contains("<p>") && html.contains("</p>"))
                        assertEquals(2, client.translator.translate(listOf("A happy day. 😀", "A\u0000B"), first).size)
                        assertTrue(client.translator.unloadModels().allReleased)
                        Log.i("foxlet-offline", "empty input, reload, HTML and Unicode checks passed")
                    }
                    Log.i("foxlet-offline", "passed $direction workers=$workers rows=${output.size}")
                } finally { client.shutdown() }
            }
        }
        assertEquals(16, results.length())
        Log.i("foxlet-offline", "all offline checks passed; report=${report.absolutePath}")
    }
}
