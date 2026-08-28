package com.ousuan.smartbutler.ui.voice

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Vosk 离线语音识别封装（不依赖 Google 服务，中国区设备可用）：
 * - 首次使用时把 assets/model 下的中文模型（vosk-model-small-cn-0.22）递归复制到内部存储，
 *   之后复用，避免重复复制 ~50MB
 * - 初始化 [Model] + [Recognizer]（16kHz 采样率）
 * - 接收 16kHz / 16bit / 单声道 PCM 音频数据，返回识别文本（JSON 中提取 text）
 *
 * 防闪退要点：
 * 1. Model()/Recognizer() 是 native 层加载，模型损坏或不完整会直接 SIGABRT 杀掉进程，
 *    Java 的 catch(Exception) 捕获不到 —— 因此在复制前先做结构预检、复制后做大小校验、
 *    用完成标记识别「上次中断残留的不完整模型」并自动删除重建。
 * 2. 初始化线程捕获 Throwable（含 UnsatisfiedLinkError 等 Error），失败走 onError 回调。
 * 3. 所有关键路径打 Log.e("VoskDebug", ...) 日志，便于定位问题。
 */
class VoskSpeechRecognizer(private val context: Context) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @Volatile
    private var ready = false

    /** 模型是否已加载完成（可开始识别） */
    val isReady: Boolean get() = ready

    /** 模型加载进度回调（主线程），如「正在加载语音模型 12/98 (12%)…」 */
    var onProgress: ((String) -> Unit)? = null

    /**
     * 异步加载模型：结构预检 → 复制 assets → 创建 Model/Recognizer。
     * 成功回调 [onReady]（主线程）；失败回调 [onError]（主线程，message 为失败原因）。
     * 幂等：已加载完成时直接回调 [onReady]。
     */
    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        if (ready) {
            Handler(Looper.getMainLooper()).post(onReady)
            return
        }
        Log.e(TAG, "模型加载开始: assets/model，目标=${File(context.filesDir, MODEL_DIR_NAME).absolutePath}")
        Thread {
            try {
                val modelDir = ensureModelReady()
                Handler(Looper.getMainLooper()).post {
                    onProgress?.invoke("模型复制完成，正在初始化识别引擎…")
                }
                Log.e(TAG, "开始创建 Model/Recognizer（native 加载，可能耗时数秒）: $modelDir")
                val m = Model(modelDir)
                val r = Recognizer(m, SAMPLE_RATE)
                model = m
                recognizer = r
                ready = true
                Log.e(TAG, "Vosk 模型加载成功")
                Handler(Looper.getMainLooper()).post(onReady)
            } catch (t: Throwable) {
                // 捕获 Throwable（含 UnsatisfiedLinkError / NoClassDefFoundError 等 Error）
                Log.e(TAG, "模型加载失败: ${t.javaClass.name}: ${t.message}", t)
                Handler(Looper.getMainLooper()).post { onError(t.message ?: "模型加载失败") }
            }
        }.start()
    }

    /**
     * 输入一帧 PCM 音频（16kHz、16bit、单声道）进行识别。
     * 检测到一句话结束时返回该句最终文本，否则返回 null
     * （中间结果可用 [getPartialText] 实时获取）。
     */
    fun startListening(audioData: ByteArray): String? {
        val r = recognizer ?: return null
        return try {
            if (r.acceptWaveForm(audioData, audioData.size)) {
                parseText(r.getResult())
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别音频输入失败: ${e.message}")
            null
        }
    }

    /** 当前这句话的实时部分识别文本（未说完时的中间结果） */
    fun getPartialText(): String = recognizer?.let { r ->
        try {
            parseText(r.getPartialResult()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "获取部分结果失败: ${e.message}")
            ""
        }
    } ?: ""

    /** 停止录音后调用：取回未消费的最终结果（一句话未主动结束时仍有输出） */
    fun getFinalText(): String = recognizer?.let { r ->
        try {
            parseText(r.getFinalResult()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "获取最终结果失败: ${e.message}")
            ""
        }
    } ?: ""

    /** 新一轮录音前重置识别状态 */
    fun reset() {
        try {
            recognizer?.reset()
        } catch (e: Exception) {
            Log.e(TAG, "识别器重置失败: ${e.message}")
        }
    }

    /** 释放识别器与模型资源（Activity 销毁时调用，需确保无并发识别） */
    fun release() {
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        try {
            model?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        model = null
        ready = false
        Log.e(TAG, "Vosk 资源已释放")
    }

    /**
     * 确保模型可用，返回模型目录绝对路径：
     * 1. 预检 assets/model 结构（am/ conf/ graph/ 及关键文件）
     * 2. 内部存储已有完整模型（含完成标记）→ 直接复用
     * 3. 残留不完整模型 → 删除重建
     * 4. 逐个复制并校验字节数，全部成功后写完成标记
     */
    @Throws(IOException::class)
    private fun ensureModelReady(): String {
        val am = context.assets
        // 1. 结构预检：避免把损坏/不完整模型喂给 native 层导致闪退
        validateAssetStructure(am)

        val destDir = File(context.filesDir, MODEL_DIR_NAME)
        val marker = File(destDir, MARKER_FILE)
        if (destDir.exists() && marker.exists()) {
            Log.e(TAG, "模型已完整存在，直接复用: ${destDir.absolutePath}")
            return destDir.absolutePath
        }
        // 2. 残留不完整模型（上次复制中断/旧版本）：删除重建
        if (destDir.exists()) {
            Log.e(TAG, "检测到不完整模型，删除后重新复制: ${destDir.absolutePath}")
            destDir.deleteRecursively()
        }

        // 3. 收集并复制
        val files = mutableListOf<String>()
        collectAssetFiles(am, ASSET_MODEL_DIR, "", files)
        if (files.isEmpty()) throw IOException("assets/model 目录为空或不存在，请确认模型文件已放置")
        val total = files.size
        var done = 0
        var copiedBytes = 0L
        for (rel in files) {
            val target = File(destDir, rel)
            target.parentFile?.mkdirs()
            // 源文件大小（解压后）作为校验基准
            val srcLen = am.open("$ASSET_MODEL_DIR/$rel").use { it.available().toLong() }
            am.open("$ASSET_MODEL_DIR/$rel").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (target.length() != srcLen) {
                throw IOException("模型文件复制不完整: $rel（期望 ${srcLen}B，实际 ${target.length()}B）")
            }
            copiedBytes += srcLen
            done++
            if (done % 5 == 0 || done == total) {
                val percent = done * 100 / total
                val msg = "正在加载语音模型 $done/$total ($percent%)…"
                Log.e(TAG, msg)
                Handler(Looper.getMainLooper()).post { onProgress?.invoke(msg) }
            }
        }
        // 4. 全部成功：写完成标记
        FileOutputStream(marker).use { it.write(1) }
        Log.e(TAG, "模型复制完成: $total 个文件，共 ${copiedBytes / 1024 / 1024}MB -> ${destDir.absolutePath}")
        return destDir.absolutePath
    }

    /** 预检 assets/model 是否含 Vosk 必需结构（am/ conf/ graph/ 及关键文件） */
    @Throws(IOException::class)
    private fun validateAssetStructure(am: AssetManager) {
        val requiredDirs = listOf("am", "conf", "graph")
        for (d in requiredDirs) {
            val list = am.list("$ASSET_MODEL_DIR/$d")
            if (list.isNullOrEmpty()) {
                throw IOException("模型缺少 $d/ 子目录，请确认 vosk-model-small-cn-0.22 已完整放置到 assets/model/")
            }
        }
        val requiredFiles = listOf("am/final.mdl", "conf/mfcc.conf", "graph/Gr.fst", "graph/HCLr.fst")
        for (f in requiredFiles) {
            try {
                am.open("$ASSET_MODEL_DIR/$f").close()
            } catch (e: IOException) {
                throw IOException("模型缺少关键文件 $f，请确认模型完整（vosk-model-small-cn-0.22）")
            }
        }
    }

    /** 解析 Vosk 返回的 JSON，提取 text 字段（无有效文本时返回 null） */
    private fun parseText(json: String): String? {
        if (json.isBlank()) return null
        return try {
            val text = JSONObject(json).optString("text", "").trim()
            text.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "解析识别 JSON 失败: $json")
            null
        }
    }

    /** 递归收集 assets 目录下所有文件路径（相对路径，目录不下发） */
    private fun collectAssetFiles(am: AssetManager, root: String, dir: String, out: MutableList<String>) {
        val path = if (dir.isEmpty()) root else "$root/$dir"
        val children = try {
            am.list(path) ?: emptyArray()
        } catch (e: IOException) {
            Log.e(TAG, "列出 assets 目录失败: $path")
            emptyArray()
        }
        if (children.isEmpty()) {
            // 无子项视为文件
            if (dir.isNotEmpty()) out.add(dir)
            return
        }
        for (c in children) {
            collectAssetFiles(am, root, if (dir.isEmpty()) c else "$dir/$c", out)
        }
    }

    companion object {
        private const val TAG = "VoskDebug"
        private const val ASSET_MODEL_DIR = "model"
        private const val MODEL_DIR_NAME = "vosk-model"
        private const val MARKER_FILE = ".complete"
        private const val SAMPLE_RATE = 16000.0f
    }
}
