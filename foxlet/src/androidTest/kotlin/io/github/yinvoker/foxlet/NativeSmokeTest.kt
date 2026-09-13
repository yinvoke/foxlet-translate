package io.github.yinvoker.foxlet

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端最小冒烟:验证 .so 装载、JNI 绑定与引擎生命周期,不依赖模型文件。
 * 注意 marian 的进程级全局态:每个用例只创建一个 service,并在用例结束前销毁,
 * 建 AsyncService 的只有 threads=2 的那两个用例(threads=1 走 BlockingService)。
 */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {

    /**
     * 自动定档在真机上的自洽性检查,不需要模型也不建 service。
     *
     * 断言三条:档位只在 {1,2,4,6} 里;SINGLE 恒 1;以及 `forDevice` 的结果与
     * 「拿它自己记下的那组输入直接调纯函数」逐字段相同(不动点)。
     *
     * 实际档位、fastCoreCount 与 MemoryInfo 四字段打到 logcat(tag
     * `foxlet-test`)备查。
     */
    @Test
    fun deviceTuningDecisionIsConsistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        Log.i(
            TAG,
            "tuning device fastCoreCount=${NativeBridge.fastCoreCount()} " +
                "availableProcessors=${Runtime.getRuntime().availableProcessors()} " +
                "totalMemMb=${mem.totalMem / MB} availMemMb=${mem.availMem / MB} " +
                "thresholdMb=${mem.threshold / MB} lowMemory=${mem.lowMemory} " +
                "isLowRamDevice=${am.isLowRamDevice}",
        )

        for (workload in Workload.entries) {
            val decision = ThreadPlanner.forDevice(context, workload)
            Log.i(TAG, "tuning workload=$workload $decision")

            assertTrue("threads=${decision.threads} has no baseline", decision.threads in setOf(1, 2, 4, 6))
            if (workload == Workload.SINGLE) assertEquals(1, decision.threads)

            // 同样的输入 -> 同样的结果。
            val pure = ThreadPlanner.recommend(
                totalRamBytes = decision.totalRamBytes,
                isLowRam = decision.isLowRam,
                bigCoreCount = decision.bigCoreCount,
                workload = workload,
            )
            assertEquals(decision, pure)
        }


    }

    @Test
    fun serviceLifecycleAndBadModelRejection() {
        // workers=1 现在是 BlockingService;cacheSize > 0 顺带验证它的
        // TranslationCache 能在进程里构造出来。
        val service = NativeBridge.createService(1, 16)
        assertNotEquals(0L, service)

        assertThrows(RuntimeException::class.java) {
            NativeBridge.loadModel(service, "models:\n  - /nonexistent/model.bin\n", null)
        }

        NativeBridge.destroyService(service)
    }

    /**
     * 真机端到端正典断言:固定句子必须译出固定文本(设备正典,SMMLA 与
     * ruy 两路径逐字节相同),并且两次调用逐字节一致(确定性)。需要模型:
     *   adb push models/enzh/ /data/local/tmp/models-enzh
     *   adb shell run-as io.github.yinvoker.foxlet.test sh -c \
     *     'mkdir -p files/models/enzh && cp /data/local/tmp/models-enzh/[the four model files] files/models/enzh/'
     * 没有模型时跳过(Assume),不算失败。
     */
    @Test
    fun canonicalSentenceWhenModelsPresent() = runBlocking {
        val dir = modelDirOrSkip()
        NativeEngine(EngineOptions(threads = 1)).use { engine ->
            val t0 = System.nanoTime()
            val first = engine.translate(listOf(CANONICAL_SOURCE), ModelFiles.fromDirectory(dir))
            val t1 = System.nanoTime()
            val second = engine.translate(listOf(CANONICAL_SOURCE), ModelFiles.fromDirectory(dir))
            val t2 = System.nanoTime()
            Log.i(TAG, "sentence threads=1 first_ms=${(t1 - t0) / 1_000_000} second_ms=${(t2 - t1) / 1_000_000}")
            assertEquals(CANONICAL_ZH, first.single())
            assertEquals(first, second)
        }
    }

    /**
     * 同一条正典句走 AsyncService(threads = 2)。单句单批,worker 数不改变
     * 批次构成,所以输出仍应是设备正典的那一行 —— 这是 async 路径没有被
     * blocking 改动碰坏的证据。跨进程的非确定性只出现在多句合批上,不在这里断言。
     */
    @Test
    fun canonicalSentenceWithWorkersWhenModelsPresent() = runBlocking {
        val dir = modelDirOrSkip()
        NativeEngine(EngineOptions(threads = 2)).use { engine ->
            val t0 = System.nanoTime()
            val out = engine.translate(listOf(CANONICAL_SOURCE), ModelFiles.fromDirectory(dir))
            val t1 = System.nanoTime()
            val again = engine.translate(listOf(CANONICAL_SOURCE), ModelFiles.fromDirectory(dir))
            val t2 = System.nanoTime()
            Log.i(TAG, "sentence threads=2 first_ms=${(t1 - t0) / 1_000_000} second_ms=${(t2 - t1) / 1_000_000}")
            assertEquals(CANONICAL_ZH, out.single())
            assertEquals(out, again)
        }
    }

    /**
     * 整语料哈希门:200 行 FLORES 一次翻完,FNV-1a 64 必须等于设备正典
     * (tools/regress-hash.sh 的 device/200 表,三台机 SMMLA/ruy 同值),
     * 再翻一次必须逐句相同。这是 smoke 二进制那道门在 AAR 侧的镜像:
     * threads=1 走 BlockingService,与 smoke --workers 0 是同一条执行路径。
     * 语料:
     *   adb push sample/src/main/assets/bench/eng.txt /data/local/tmp/eng200.txt
     *   adb shell run-as io.github.yinvoker.foxlet.test sh -c \
     *     'mkdir -p files/bench && cp /data/local/tmp/eng200.txt files/bench/'
     * 没有语料或模型时跳过(Assume),不算失败。
     */
    @Test
    fun corpusHashMatchesDeviceCanonicalWhenPresent() = runBlocking {
        val dir = modelDirOrSkip()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpusFile = File(context.filesDir, "bench/eng200.txt")
        Assume.assumeTrue("no corpus at ${corpusFile.absolutePath}", corpusFile.isFile)
        val corpus = corpusFile.readLines().dropLastWhile { it.isEmpty() }
        Assume.assumeTrue("expected $CORPUS_LINES lines, got ${corpus.size}", corpus.size == CORPUS_LINES)

        // device/200 正典使用英文前缀表和 batch 512。
        NativeEngine(EngineOptions(threads = 1, miniBatchWords = 512)).use { engine ->
            val model = ModelFiles.fromDirectory(dir)
            // first_ms includes service creation and the lazy model load;
            // second/third are warm. Logged for the app-path latency record.
            val t0 = System.nanoTime()
            val first = engine.translate(corpus, model)
            val t1 = System.nanoTime()
            val second = engine.translate(corpus, model)
            val t2 = System.nanoTime()
            val third = engine.translate(corpus, model)
            val t3 = System.nanoTime()
            val hash = fnv1a64(first)
            Log.i(
                TAG,
                "corpus threads=1 first_ms=${(t1 - t0) / 1_000_000} second_ms=${(t2 - t1) / 1_000_000} " +
                    "third_ms=${(t3 - t2) / 1_000_000} hash=$hash",
            )
            assertEquals(corpus.size, first.size)
            assertEquals(CANONICAL_DEVICE_HASH_200, hash)
            assertEquals(first, second)
            assertEquals(first, third)
        }
    }

    /** AsyncService 两线程的批量输出须匹配 device/200 正典，并保持重复调用一致。 */
    @Test
    fun corpusHashMatchesDeviceCanonicalWithWorkersWhenModelsPresent() = runBlocking {
        val dir = modelDirOrSkip()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpusFile = File(context.filesDir, "bench/eng200.txt")
        Assume.assumeTrue("no corpus at ${corpusFile.absolutePath}", corpusFile.isFile)
        val corpus = corpusFile.readLines().dropLastWhile { it.isEmpty() }
        Assume.assumeTrue("expected $CORPUS_LINES lines, got ${corpus.size}", corpus.size == CORPUS_LINES)

        NativeEngine(EngineOptions(threads = 2, miniBatchWords = 512)).use { engine ->
            val model = ModelFiles.fromDirectory(dir)
            val t0 = System.nanoTime()
            val first = engine.translate(corpus, model)
            val t1 = System.nanoTime()
            val second = engine.translate(corpus, model)
            val t2 = System.nanoTime()
            val hash = fnv1a64(first)
            Log.i(
                TAG,
                "corpus threads=2 first_ms=${(t1 - t0) / 1_000_000} second_ms=${(t2 - t1) / 1_000_000} hash=$hash",
            )
            assertEquals(corpus.size, first.size)
            assertEquals(CANONICAL_DEVICE_HASH_200, hash)
            assertEquals(first, second)
        }
    }

    /**
     * 分句前缀表在 app 路径上真的接上了引擎,且不改变批次的输入输出契约。
     *
     * 不断言任何具体译文:只要求(1)开/关两条路都能把整批翻完、每行一条输出、
     * 没有空行;(2)两批结果至少有一行不同 —— 这就是「字节确实喂给了 ssplit」的
     * 证据。语料里每行都带缩写/小数/编号,关掉前缀表时正则会在 `Dr.`、`U.S.`、
     * `No. 5` 这些地方切断句子,开着时不会。
     *
     * 顺带压一遍 close() 的同步语义:两个 engine 先后建同一个 service。
     */
    @Test
    fun abbreviationsTranslateWithAndWithoutPrefixTable() = runBlocking {
        val dir = modelDirOrSkip()
        val model = ModelFiles.fromDirectory(dir)
        assertEquals("en", model.sourceLanguage)

        val withTable = NativeEngine(EngineOptions(threads = 1)).use { engine ->
            engine.translate(ABBREVIATION_LINES, model)
        }
        val withoutTable = NativeEngine(EngineOptions(threads = 1, nonbreakingPrefixes = false)).use { engine ->
            engine.translate(ABBREVIATION_LINES, model)
        }
        Log.i(TAG, "ssplit with=$withTable")
        Log.i(TAG, "ssplit without=$withoutTable")

        assertEquals(ABBREVIATION_LINES.size, withTable.size)
        assertEquals(ABBREVIATION_LINES.size, withoutTable.size)
        assertTrue("blank output with the prefix table", withTable.none { it.isBlank() })
        assertTrue("blank output without the prefix table", withoutTable.none { it.isBlank() })
        assertNotEquals("the prefix table changed nothing -- did the bytes reach ssplit?", withoutTable, withTable)
    }

    /**
     * 空闲回收在真机上真的会自己发生:保温期设成 1.5 s,翻一句,然后什么都不做
     * 地等 3 s,常驻模型数必须自己回到 0(定时清扫跑过了,原生侧已析构)。
     * 之后再翻同一句仍要译对 —— 重载对调用方透明 —— 重载那一句的耗时打到
     * logcat(tag `foxlet-test`),用来对账「保温多久才划算」。
     * 需要模型,没有则跳过(Assume),不算失败。
     */
    @Test
    fun idleUnloadReclaimsTheModelAndReloadsOnDemand() = runBlocking {
        val dir = modelDirOrSkip()
        NativeEngine(EngineOptions(threads = 1, idleUnloadMillis = IDLE_MILLIS)).use { engine ->
            val model = ModelFiles.fromDirectory(dir)
            assertEquals(CANONICAL_ZH, engine.translate(listOf(CANONICAL_SOURCE), model).single())
            assertEquals("model should be resident right after a translation", 1, engine.loadedModelCount().get())

            delay(2 * IDLE_MILLIS)
            assertEquals("idle sweep did not reclaim the model", 0, engine.loadedModelCount().get())

            val t0 = System.nanoTime()
            val again = engine.translate(listOf(CANONICAL_SOURCE), model)
            val reloadMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "idle-unload idleMs=$IDLE_MILLIS reload_plus_sentence_ms=$reloadMs")
            assertEquals(CANONICAL_ZH, again.single())
            assertEquals(1, engine.loadedModelCount().get())
        }
    }

    private fun modelDirOrSkip(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "models/enzh")
        Assume.assumeTrue(
            "no enzh model under ${dir.absolutePath}",
            dir.listFiles()?.any { it.name.endsWith(".bin") } == true,
        )
        return dir
    }

    private companion object {
        const val TAG = "foxlet-test"

        const val MB = 1024L * 1024L

        const val CANONICAL_SOURCE =
            "\"We now have 4-month-old mice that are non-diabetic that used to be diabetic,\" he added."

        // Mi 14 (8 Gen 3) and Mi 10 (865), SMMLA and ruy paths: identical bytes.
        const val CANONICAL_ZH = "他补充道:“我们现在有4个月大的小鼠,它们是非糖尿病,曾经患有糖尿病。”"

        /** 空闲回收用例的保温期。够短能等得起,又远长于一次翻译。 */
        const val IDLE_MILLIS = 1_500L

        const val CORPUS_LINES = 200

        /** 每行都含一个「句号不结束句子」的缩写、小数或编号。 */
        val ABBREVIATION_LINES = listOf(
            "Dr. Smith examined the patient and sent the results to Mr. Jones the next morning.",
            "U.S. President George W. Bush welcomed the announcement.",
            "Please read No. 5 and No. 12 before you sign the contract.",
            "Several agencies, e.g. the FAA and the FCC, published new rules last week.",
            "Inflation reached 3.5 percent, the highest level since 2011.",
        )

        /** tools/regress-hash.sh, device/200 en→zh, nonbreaking-prefix table on. */
        const val CANONICAL_DEVICE_HASH_200 = "88295d89303c20bd"

        const val FNV_OFFSET_BASIS = 1469598103934665603L
        const val FNV_PRIME = 1099511628211L

        /**
         * 与 smoke 的 responseHash() 逐位相同:对每句的 UTF-8 字节做 FNV-1a 64,
         * 每句之后再吃一个 '\n'。Long 的溢出回绕就是无符号 64 位乘法。
         */
        fun fnv1a64(lines: List<String>): String {
            var h = FNV_OFFSET_BASIS
            for (line in lines) {
                for (byte in line.toByteArray(Charsets.UTF_8)) {
                    h = (h xor (byte.toLong() and 0xffL)) * FNV_PRIME
                }
                h = (h xor '\n'.code.toLong()) * FNV_PRIME
            }
            return String.format(Locale.US, "%016x", h)
        }
    }
}
