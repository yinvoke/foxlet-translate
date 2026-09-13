package io.github.yinvoker.foxlet.demo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import io.github.yinvoker.foxlet.*
import java.io.File
import kotlinx.coroutines.*

/** Minimal consumer: model download, integrity check, offline translation, error/retry, local model management. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var operation: Job? = null
    private var model: InstalledModel? = null
    private val pair = LanguagePair("en", "zh-Hans")
    private suspend fun client(): Foxlet = DemoClient.get(applicationContext)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val dp = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            column.addView(this)
        }
        label("Foxlet Translate", 28f)
        label("英译中 · 在你的手机上翻译", 18f)
        label("首次使用需要下载模型。准备完成后可关闭网络，输入文本不会上传。只有下载模型和点击“检查更新”时才联网，后者向 Mozilla 请求一次模型索引。", 15f)
        val input = EditText(this).apply {
            hint = "输入英文"; setText("Hello, world! Have a great day. 😀")
            minLines = 4; gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        column.addView(input)
        val status = label("模型未准备", 14f)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        column.addView(progress)
        val prepare = Button(this).apply { text = "下载 / 检查模型" }
        column.addView(prepare)
        val translate = Button(this).apply { text = "翻译"; isEnabled = false }
        column.addView(translate)
        val cancel = Button(this).apply { text = "取消"; visibility = View.GONE }
        column.addView(cancel)
        val output = label("译文会显示在这里", 20f).apply { setTextIsSelectable(true) }
        // Model management: created here so busy() can reach them, added to the column below.
        val manageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowButton(value: String) = Button(this).apply {
            text = value
            manageRow.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val listInstalled = rowButton("已安装")
        val checkUpdates = rowButton("检查更新")
        val cleanup = rowButton("清理")
        fun busy(value: Boolean) {
            prepare.isEnabled = !value
            translate.isEnabled = !value && model != null
            cancel.visibility = if (value) View.VISIBLE else View.GONE
            listInstalled.isEnabled = !value; checkUpdates.isEnabled = !value; cleanup.isEnabled = !value
        }
        prepare.setOnClickListener {
            busy(true); progress.visibility = View.VISIBLE; status.text = "正在准备模型…"
            operation = scope.launch {
                try {
                    var lastUpdate = 0L
                    model = client().models.prepare(pair) { p ->
                        val done = p.downloadedBytes
                        val total = p.totalBytes
                        val now = System.nanoTime()
                        if (done == total || now - lastUpdate > 100_000_000) {
                            lastUpdate = now
                            runOnUiThread { progress.progress = if (total > 0) (done * 100 / total).toInt() else 0 }
                        }
                    }
                    status.text = "模型已校验，可断网翻译"
                } catch (e: CancellationException) { status.text = "已取消，可以重试"; throw e }
                catch (e: Exception) { status.text = "准备失败：${e.message}。请重试。" }
                finally { progress.visibility = View.GONE; busy(false) }
            }
        }
        translate.setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) { input.error = "请输入英文"; return@setOnClickListener }
            if (text.length > 20_000) { input.error = "演示每次最多输入 20000 个字符"; return@setOnClickListener }
            busy(true); status.text = "正在本地翻译…"
            operation = scope.launch {
                try {
                    val ready = checkNotNull(model)
                    val start = System.nanoTime()
                    val result = client().translator.translate(text, ready)
                    output.text = result
                    status.text = "翻译完成 · ${(System.nanoTime() - start) / 1_000_000} ms（含加载）"
                } catch (e: CancellationException) { status.text = "已取消，当前批次结束后释放资源"; throw e }
                catch (e: Exception) { status.text = "翻译失败：${e.message}" }
                finally { busy(false) }
            }
        }
        cancel.setOnClickListener { operation?.cancel() }

        // ---- 模型管理：枚举本地模型、显式检查 Mozilla 更新、回收旧版本。除“检查更新”外都不联网。
        label("模型管理", 18f)
        val manageStatus = label("点击“已安装”查看本地模型；“检查更新”只在点击时向 Mozilla 请求一次索引，不会自动联网。", 14f)
        column.addView(manageRow)
        fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)
        fun describe(models: List<InstalledModel>) =
            if (models.isEmpty()) "本地没有模型" else models.joinToString("\n") { m ->
                "${m.pair.source}→${m.pair.target} ${m.version} · ${mb(m.sizeBytes)} · ${if (m.isBundledVersion) "SDK 内置版本" else "其他版本"}"
            }
        /** Runs [block] on IO and shows its text (or the error) in the management status line. */
        fun manage(start: String, block: suspend () -> String) {
            busy(true); manageStatus.text = start
            operation = scope.launch {
                try { manageStatus.text = block() }
                catch (e: CancellationException) { manageStatus.text = "已取消"; throw e }
                catch (e: Exception) { manageStatus.text = "失败：${e.message}" }
                finally { busy(false) }
            }
        }
        fun downloadUpdate(candidate: ModelDescriptor) {
            busy(true); progress.visibility = View.VISIBLE; manageStatus.text = "正在下载 ${candidate.pair.source}→${candidate.pair.target} ${candidate.version}…"
            operation = scope.launch {
                try {
                    var lastUpdate = 0L
                    val files = client().models.download(candidate, DownloadOptions(maxRetries = 5)) { p ->
                            val now = System.nanoTime()
                            if (p.downloadedBytes == p.totalBytes || now - lastUpdate > 100_000_000) {
                                lastUpdate = now
                                runOnUiThread {
                                    if (p.totalBytes > 0) progress.progress = (p.downloadedBytes * 100 / p.totalBytes).toInt()
                                    if (p.attempt > 1) manageStatus.text = "正在重试 ${p.assetName}（第 ${p.attempt} 次）"
                                }
                            }
                    }
                    model = files   // Later translations use the new directory; the old one is left for "清理".
                    manageStatus.text = "已切换到 ${candidate.version}；旧版本可用“清理”回收"
                    status.text = "模型已校验，可断网翻译"
                } catch (e: CancellationException) { manageStatus.text = "已取消，已下载的部分会在下次继续"; throw e }
                catch (e: Exception) { manageStatus.text = "更新失败：${e.message}" }
                finally { progress.visibility = View.GONE; busy(false) }
            }
        }
        listInstalled.setOnClickListener {
            manage("正在扫描本地模型…") { describe(client().models.listInstalled()) }
        }
        cleanup.setOnClickListener {
            val keep = setOfNotNull(model?.id)
            manage("正在清理…") {
                val r = client().models.cleanup(keep = keep)
                "清理完成：临时目录 ${r.removedTempDirectories.size}，旧版本 ${r.removedSuperseded.size}，释放 ${mb(r.freedBytes)}，使用中跳过 ${r.skippedInUse.size}"
            }
        }
        checkUpdates.setOnClickListener {
            busy(true); manageStatus.text = "正在向 Mozilla 请求模型索引…"
            operation = scope.launch {
                try {
                    val report = client().models.checkUpdates()
                    val enZh = report.assessments.firstOrNull { it.pair == pair }
                    val available = enZh?.target
                    manageStatus.text = "可更新 ${report.updates.size} 个，未安装 ${report.notInstalled.size} 个；en→zh-Hans：" + when {
                        enZh == null || available == null -> "上游未列出"
                        enZh.state == UpdateState.UpdateAvailable -> "有更新 ${available.version}（${mb(available.sizeBytes)}）"
                        enZh.installed == null -> "未安装"
                        enZh.state == UpdateState.LocalAhead -> "本地版本较新"
                        else -> "与上游一致"
                    } + (enZh?.newerMajorVersion?.let { "；上游已有 $it，需升级 SDK" } ?: "") +
                        (if (enZh != null && !enZh.installedStillListed) "；已安装版本已不在 Mozilla 列表中" else "")
                    if (enZh != null && enZh.state == UpdateState.UpdateAvailable && available != null) {
                        AlertDialog.Builder(this@MainActivity).setTitle("下载更新")
                            .setMessage("en→zh-Hans ${available.version}，约 ${mb(available.sizeBytes)}。下载到新目录，当前模型不受影响。")
                            .setPositiveButton("下载") { _, _ -> downloadUpdate(available) }
                            .setNegativeButton("稍后", null).show()
                    }
                } catch (e: CancellationException) { manageStatus.text = "已取消"; throw e }
                catch (e: Exception) { manageStatus.text = "检查失败：${e.message}" }
                finally { busy(false) }
            }
        }

        column.addView(Button(this).apply {
            text = "开源许可与源码"
            setOnClickListener {
                scope.launch {
                    val notice = withContext(Dispatchers.IO) {
                        listOf("SOURCE.txt", "NOTICE.txt", "THIRD_PARTY_NOTICES.txt").joinToString("\n\n") { name ->
                            javaClass.getResourceAsStream("/io/github/yinvoker/foxlet/licenses/$name")?.bufferedReader()?.use { it.readText() }
                                ?: "$name unavailable"
                        }
                    }
                    AlertDialog.Builder(this@MainActivity).setTitle("开源许可与源码").setMessage(notice).setPositiveButton("关闭", null).show()
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Application lifetime ownership: Activity recreation/cancellation does not tear down another page's client. */
private object DemoClient {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var value: Foxlet? = null
    suspend fun get(context: android.content.Context): Foxlet {
        mutex.lock()
        return try {
            value ?: Foxlet.create(context) {
                models { directory = File(context.filesDir, "models") }
            }.also { value = it }
        } finally { mutex.unlock() }
    }
}
