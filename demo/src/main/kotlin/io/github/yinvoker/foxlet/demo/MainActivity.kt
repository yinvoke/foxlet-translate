package io.github.yinvoker.foxlet.demo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import io.github.yinvoker.foxlet.BergamotEngine
import io.github.yinvoker.foxlet.ModelCatalog
import io.github.yinvoker.foxlet.ModelFiles
import java.io.File
import kotlinx.coroutines.*

/** Minimal consumer: model download, integrity check, offline translation, error/retry. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var operation: Job? = null
    private var model: ModelFiles? = null
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
        label("首次使用需要下载模型。准备完成后可关闭网络，输入文本不会上传。", 15f)
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
        fun busy(value: Boolean) {
            prepare.isEnabled = !value
            translate.isEnabled = !value && model != null
            cancel.visibility = if (value) View.VISIBLE else View.GONE
        }
        prepare.setOnClickListener {
            busy(true); progress.visibility = View.VISIBLE; status.text = "正在准备模型…"
            operation = scope.launch {
                try {
                    var lastUpdate = 0L
                    model = ModelCatalog.download(File(filesDir, "models"), "en", "zh-Hans") { done, total ->
                        val now = System.nanoTime()
                        if (done == total || now - lastUpdate > 100_000_000) {
                            lastUpdate = now
                            runOnUiThread { progress.progress = (done * 100 / total).toInt() }
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
                    val result = withContext(Dispatchers.IO) {
                        BergamotEngine().use { it.translate(listOf(text), ready).single() }
                    }
                    output.text = result
                    status.text = "翻译完成 · ${(System.nanoTime() - start) / 1_000_000} ms（含加载）"
                } catch (e: CancellationException) { status.text = "已取消，当前批次结束后释放资源"; throw e }
                catch (e: Exception) { status.text = "翻译失败：${e.message}" }
                finally { busy(false) }
            }
        }
        cancel.setOnClickListener { operation?.cancel() }
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
