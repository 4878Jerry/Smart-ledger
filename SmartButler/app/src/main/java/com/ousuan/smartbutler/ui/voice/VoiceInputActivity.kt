package com.ousuan.smartbutler.ui.voice

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.data.network.BaiduNlpCorrector
import com.ousuan.smartbutler.data.network.NetworkMonitor
import com.ousuan.smartbutler.databinding.ActivityVoiceInputBinding
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.DateUtils
import com.ousuan.smartbutler.util.ParseUtils
import com.ousuan.smartbutler.util.fmtMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 语音记账（双模式：Vosk 离线 + 百度在线备选）：
 * - 离线模式（默认，原行为）：AudioRecord 录制 16kHz/16bit/单声道 PCM → 实时送入
 *   [VoskSpeechRecognizer] → 识别文本实时显示 → 本地正则提取金额与分类（复用 ParseUtils）。
 * - 在线模式（开关开启，需在 ApiConfig.BaiduAsr 配置 Key）：录音累积 PCM → 停止后一次性
 *   上传百度在线识别（BaiduAsrManager，REST 替代已下架 asr-sdk）→ 失败/无结果自动降级
 *   同一段 PCM 喂 Vosk，不打断用户。
 * - 两种模式识别文本统一走：本地解析（含同音字纠正）→ 联网时叠加百度 NLP 在线纠错
 *   （修正「一白领五」→「一百零五」类同音错字后重新提取，断网/接口失败自动回退本地结果）→
 *   预览可修改后保存。
 *
 * 防闪退要点（配合 Logcat 过滤 VoskDebug 定位）：
 * - onCreate/initVosk 全程 try-catch，模型加载失败只提示不崩溃
 * - AudioRecord 初始化参数校验，设备不支持时友好提示
 * - 录音线程捕获所有异常并安全收尾
 * - onDestroy 先 join 录音线程再释放 Vosk，避免 native 层并发 use-after-free
 */
class VoiceInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceInputBinding
    private val repository by lazy { (application as SmartButlerApp).repository }
    private val vosk by lazy { VoskSpeechRecognizer(this) }
    private val baiduAsr by lazy { BaiduAsrManager() }
    private val modePrefs by lazy { getSharedPreferences("voice_mode", MODE_PRIVATE) }

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var lastFinalText: String? = null
    /** 当前录音是否走百度在线模式（true 时 PCM 只累积不上送 Vosk） */
    private var usingBaidu = false
    /** 在线模式录音期间累积的 PCM（16k/16bit/单声道），停止后一次性上传百度 */
    private var pcmBuffer: ByteArrayOutputStream? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.e("VoskDebug", "录音权限回调: granted=$granted")
            if (granted) {
                startVoiceInput()
            } else {
                // 拒绝录音权限：弹窗引导去系统设置开启
                showPermissionDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityVoiceInputBinding.inflate(layoutInflater)
            setContentView(binding.root)
            supportActionBar?.title = getString(R.string.voice_title)

            binding.spCategory.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                Categories.EXPENSE
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            binding.btnListen.setOnClickListener {
                if (isRecording) {
                    stopRecording()
                } else {
                    startVoiceInput()
                }
            }
            binding.btnSave.setOnClickListener { save() }

            // 识别模式开关：在线（百度优先，失败降级 Vosk）/ 离线（纯 Vosk），选择持久化，重启后保留
            binding.swVoiceMode.isChecked = isOnlineMode()
            binding.swVoiceMode.setOnCheckedChangeListener { _, checked ->
                modePrefs.edit().putBoolean("online", checked).apply()
                Log.d("VoiceDebug", "识别模式切换: ${if (checked) "在线（百度优先）" else "离线（Vosk）"} 百度已配置=${baiduAsr.isConfigured}")
                if (checked && !baiduAsr.isConfigured) {
                    Toast.makeText(
                        this,
                        "未配置百度 Key（ApiConfig.BaiduAsr.API_KEY/SECRET_KEY），在线识别将自动降级为离线",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // 模型加载不需要录音权限，进入页面即开始加载
            initVosk()
            Log.e("VoskDebug", "onCreate 完成")
        } catch (t: Throwable) {
            Log.e("VoskDebug", "onCreate 初始化异常: ${t.javaClass.name}: ${t.message}", t)
            Toast.makeText(this, "语音页面初始化失败，请重试", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * 加载 Vosk 模型：期间禁用录音按钮并显示进度；完成启用；失败给出降级提示。
     * 全程捕获异常，模型加载失败不崩溃。
     */
    private fun initVosk() {
        try {
            binding.btnListen.isEnabled = false
            binding.tvRecognized.text = "正在加载语音模型…"
            Log.e("VoskDebug", "initVosk 开始，当前模型就绪状态=${vosk.isReady}")
            vosk.onProgress = { msg ->
                if (isActive()) binding.tvRecognized.text = msg
            }
            vosk.init(
                onReady = {
                    if (!isActive()) return@init
                    Log.e("VoskDebug", "initVosk 回调 onReady，启用录音按钮")
                    binding.btnListen.isEnabled = true
                    binding.tvRecognized.text = "语音模型就绪，点击「开始录音」说话"
                },
                onError = { msg ->
                    Log.e("VoskDebug", "initVosk 回调 onError: $msg")
                    if (isActive()) {
                        binding.btnListen.isEnabled = false
                        binding.tvRecognized.text = "语音识别不可用，请使用手动输入"
                        Toast.makeText(
                            this,
                            "语音识别不可用，请使用手动输入",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("VoskDebug", "initVosk 异常: ${t.javaClass.name}: ${t.message}", t)
            binding.btnListen.isEnabled = false
            binding.tvRecognized.text = "语音识别不可用，请使用手动输入"
        }
    }

    /** 入口：查录音权限 → 按模式分流（在线=百度优先，离线=Vosk），都失败自动降级 */
    private fun startVoiceInput() {
        Log.e("VoskDebug", "startVoiceInput: 模型就绪=${vosk.isReady} 在线模式=${isOnlineMode()} 百度已配置=${baiduAsr.isConfigured}")
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("VoskDebug", "未授予录音权限，发起动态申请")
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        if (isOnlineMode() && baiduAsr.isConfigured) {
            if (!NetworkMonitor.isConnected()) {
                // 在线模式但无网络：不阻塞录音，直接降级 Vosk
                Log.d("VoiceDebug", "在线模式但无网络，降级 Vosk 离线识别")
                Toast.makeText(this, "无网络，已切换离线识别", Toast.LENGTH_SHORT).show()
            } else {
                startRecording(useBaidu = true)
                return
            }
        }
        if (!vosk.isReady) {
            Log.e("VoskDebug", "模型尚未就绪，等待加载")
            binding.tvRecognized.text = "语音模型加载中，请稍候…"
            return
        }
        startRecording(useBaidu = false)
    }

    /**
     * 初始化 AudioRecord（16000Hz / 16bit / 单声道），失败友好提示不崩溃。
     * [useBaidu]=true 时录音数据只累积 PCM，停止后一次性上传百度在线识别（REST 无流式结果）；
     * =false 时实时送入 Vosk 离线识别（原流程）。
     */
    private fun startRecording(useBaidu: Boolean = false) {
        Log.e("VoskDebug", "startRecording: 采样率=16000, 编码=PCM_16BIT, 声道=MONO, 百度在线=$useBaidu")
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        Log.e("VoskDebug", "AudioRecord.getMinBufferSize 返回: $bufferSize")
        if (bufferSize <= 0) {
            Log.e("VoskDebug", "设备不支持该录音配置（bufferSize=$bufferSize）")
            Toast.makeText(this, "当前设备不支持此录音配置，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (e: Exception) {
            Log.e("VoskDebug", "创建 AudioRecord 失败: ${e.javaClass.name}: ${e.message}", e)
            Toast.makeText(this, "无法初始化录音设备，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoskDebug", "AudioRecord 未就绪: state=${record.state}")
            record.release()
            Toast.makeText(this, "无法初始化录音设备，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        audioRecord = record
        usingBaidu = useBaidu
        pcmBuffer = if (useBaidu) ByteArrayOutputStream() else null
        if (!useBaidu) vosk.reset()
        lastFinalText = null
        isRecording = true
        binding.btnListen.text = "停止录音"
        binding.tvRecognized.text =
            if (useBaidu) "正在聆听（在线识别，停止后出结果）…" else "正在聆听，请说话…"
        try {
            record.startRecording()
            Log.e("VoskDebug", "录音已开始")
        } catch (e: Exception) {
            Log.e("VoskDebug", "startRecording 失败: ${e.javaClass.name}: ${e.message}", e)
            isRecording = false
            record.release()
            audioRecord = null
            binding.btnListen.text = "开始录音"
            Toast.makeText(this, "录音启动失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        recordingThread = Thread {
            try {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    // 16bit 采样：确保送入 Vosk 的字节数为偶数
                    val valid = read - (read % 2)
                    if (valid <= 0) continue
                    if (usingBaidu) {
                        // 在线模式：累积 PCM，停止后一次性上传百度识别
                        pcmBuffer?.write(buffer, 0, valid)
                    } else {
                        // 实时送入 Vosk：一句话结束返回最终文本，否则取部分结果实时显示
                        val finalText = vosk.startListening(buffer.copyOf(valid))
                        if (finalText != null) {
                            lastFinalText = finalText
                            Log.e("VoskDebug", "识别到完整结果: $finalText")
                            runOnUiThread { binding.tvRecognized.text = finalText }
                        } else {
                            val partial = vosk.getPartialText()
                            if (partial.isNotEmpty()) {
                                Log.e("VoskDebug", "部分识别结果: $partial")
                                runOnUiThread { binding.tvRecognized.text = partial }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("VoskDebug", "录音线程异常: ${t.javaClass.name}: ${t.message}", t)
            } finally {
                runOnUiThread {
                    if (isRecording) {
                        Log.e("VoskDebug", "录音线程意外退出，恢复按钮状态")
                        isRecording = false
                        binding.btnListen.text = "开始录音"
                        binding.tvRecognized.text = "录音意外中断，请重试"
                    }
                }
            }
        }.apply { start() }
    }

    private fun stopRecording() {
        Log.e("VoskDebug", "stopRecording: 停止录音, 百度在线=$usingBaidu")
        isRecording = false
        recordingThread?.join(1000)
        val record = audioRecord
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        audioRecord = null
        recordingThread = null
        binding.btnListen.text = "开始录音"
        Log.e("VoskDebug", "录音已停止，取回最终结果")

        if (usingBaidu) {
            usingBaidu = false
            val pcm = pcmBuffer?.toByteArray()
            pcmBuffer = null
            finalizeBaiduOnline(pcm)
            return
        }

        // 取未消费的最终结果（优先 flush 结果，其次一句话结束时的结果）
        val flushed = vosk.getFinalText()
        vosk.reset()
        val text = if (flushed.isNotEmpty()) flushed else lastFinalText
        Log.e("VoskDebug", "识别结果: flushed=${flushed.isNotEmpty()} final=$text")
        if (!text.isNullOrEmpty()) {
            binding.tvRecognized.text = text
            // 1) 先本地即时解析（含同音字纠正），保证离线/纠错失败时有兜底
            fillPreview(text)
            // 2) 联网时叠加百度 NLP 在线纠错：修正同音错字后重新提取金额/分类
            refillWithBaiduCorrection(text)
        } else {
            binding.tvRecognized.text = "未识别到有效内容，请手动输入"
        }
    }

    /**
     * 在线模式收尾：把录音 PCM 上传百度在线识别；失败/无结果自动降级——
     * 将同一段 PCM 喂给 Vosk 离线识别（不打断用户、无需重新录音）。
     * 识别成功后与离线路径一致：本地解析 + 百度 NLP 纠错。
     */
    private fun finalizeBaiduOnline(pcm: ByteArray?) {
        if (pcm == null || pcm.isEmpty()) {
            Log.e("VoskDebug", "在线模式无录音数据")
            binding.tvRecognized.text = "未识别到有效内容，请手动输入"
            return
        }
        binding.tvRecognized.text = "正在识别（在线）…"
        lifecycleScope.launch {
            var text = if (baiduAsr.isConfigured) baiduAsr.recognize(pcm) else null
            if (text.isNullOrBlank()) {
                Log.e("VoskDebug", "百度在线识别失败/无结果，降级 Vosk 识别同段音频")
                text = withContext(Dispatchers.IO) { voskRecognizeOnce(pcm) }
                if (text.isNullOrBlank()) {
                    if (isActive()) binding.tvRecognized.text = "未识别到有效内容，请手动输入"
                    return@launch
                }
            }
            if (!isActive()) return@launch
            Log.e("VoskDebug", "在线识别结果: $text")
            binding.tvRecognized.text = text
            // 与离线路径一致的解析链：本地即时解析 → 联网叠加百度 NLP 纠错
            fillPreview(text)
            refillWithBaiduCorrection(text)
        }
    }

    /** 把一段 PCM 一次性喂给 Vosk（百度降级路径），返回拼接识别文本；失败返回 null */
    private fun voskRecognizeOnce(pcm: ByteArray): String? {
        if (!vosk.isReady) {
            Log.e("VoskDebug", "Vosk 模型未就绪，无法降级识别")
            return null
        }
        return try {
            vosk.reset()
            val parts = mutableListOf<String>()
            var offset = 0
            val chunk = 8000
            while (offset < pcm.size) {
                val len = minOf(chunk, pcm.size - offset)
                vosk.startListening(pcm.copyOfRange(offset, offset + len))?.let { parts.add(it) }
                offset += len
            }
            val tail = vosk.getFinalText()
            if (tail.isNotEmpty()) parts.add(tail)
            vosk.reset()
            parts.joinToString("").ifEmpty { null }
        } catch (t: Throwable) {
            Log.e("VoskDebug", "Vosk 降级识别异常: ${t.javaClass.name}: ${t.message}", t)
            null
        }
    }

    /** 识别模式开关状态（SharedPreferences 持久化，默认离线=原行为） */
    private fun isOnlineMode(): Boolean = modePrefs.getBoolean("online", false)

    /**
     * 解析识别文本，自动填入预览控件：
     * - 金额：正则提取（支持「38块钱」「15块」「¥35」及无单位「50」），提取不到时 Toast 提示手动输入
     * - 分类：关键词匹配（吃/饭→餐饮，打车→交通，书→学习 等），未命中回退「其他」
     * - 备注：整段识别文字填入
     */
    private fun fillPreview(text: String) {
        Log.e("VoskDebug", "fillPreview: $text")
        // 同音字纠错（白→百、领→零），解决「一白领五」→「一百零五」类识别误差
        val corrected = ParseUtils.correctHomophones(text)
        val amount = ParseUtils.extractAmount(corrected)
        val category = ParseUtils.extractCategory(corrected)
        Log.d("VoiceDebug", "原始识别文本: $text")
        Log.d("VoiceDebug", "纠错后文本: $corrected")
        Log.d("VoiceDebug", "提取金额: $amount, 分类: $category")
        if (amount != null) {
            binding.etAmount.setText(fmtMoney(amount))
            Log.e("VoskDebug", "自动填入金额: $amount")
        } else {
            Log.e("VoskDebug", "未识别到金额: $text")
            Toast.makeText(this, "未识别到金额，请手动输入", Toast.LENGTH_SHORT).show()
        }
        binding.spCategory.setSelection(Categories.EXPENSE.indexOf(category).coerceAtLeast(0))
        Log.e("VoskDebug", "自动选择分类: $category")
        binding.etNote.setText(text)
    }

    /**
     * 联网时叠加百度 NLP 在线纠错（不阻塞 UI）：
     * - 纠错成功：用纠错后文本（如「一白领五」→「一百零五」）重新提取金额/分类并刷新预览；
     * - 断网 / 未配置 Key / 接口失败：保持本地解析结果，不影响现有 Vosk 离线流程。
     * 若用户已手动修改过金额，则不再覆盖，避免误操作。
     */
    private fun refillWithBaiduCorrection(original: String) {
        if (!NetworkMonitor.isConnected()) {
            Log.d("VoiceDebug", "断网，跳过百度在线纠错，使用 Vosk 原始结果")
            return
        }
        lifecycleScope.launch {
            val corrected = BaiduNlpCorrector.correct(original)
            if (corrected.isNullOrBlank()) {
                Log.d("VoiceDebug", "百度纠错无结果，保持本地解析结果")
                return@launch
            }
            if (!isActive()) return@launch
            Log.d("VoiceDebug", "百度纠错后文本: $corrected")
            val amount = ParseUtils.extractAmount(corrected)
            val category = ParseUtils.extractCategory(corrected)
            Log.d("VoiceDebug", "纠错后提取金额: $amount, 分类: $category")
            // 金额有变化才覆盖（用户手动输入过则保留）
            val currentAmount = binding.etAmount.text.toString().trim().toDoubleOrNull()
            if (amount != null && amount != currentAmount) {
                binding.etAmount.setText(fmtMoney(amount))
                Log.e("VoskDebug", "百度纠错后更新金额: $amount")
            }
            val currentCategory = binding.spCategory.selectedItem?.toString()
            if (category.isNotEmpty() && category != currentCategory) {
                binding.spCategory.setSelection(Categories.EXPENSE.indexOf(category).coerceAtLeast(0))
                Log.e("VoskDebug", "百度纠错后更新分类: $category")
            }
        }
    }

    /** 拒绝录音权限：弹窗引导去系统设置开启 */
    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("请授权录音权限")
            .setMessage("语音记账需要麦克风（录音）权限，请在系统设置中开启「录音」权限后再试")
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("VoskDebug", "打开应用设置失败: ${e.message}")
                    Toast.makeText(
                        this,
                        "无法打开系统设置，请手动前往「设置-应用-智能管家-权限」开启录音权限",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun save() {
        val amount = binding.etAmount.text.toString().trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.etAmount.error = "请输入有效金额"
            return
        }
        val transaction = Transaction(
            date = DateUtils.today(),
            type = "支出",
            category = binding.spCategory.selectedItem?.toString() ?: "其他",
            amount = amount,
            payee = "",
            note = binding.etNote.text.toString().trim()
        )
        lifecycleScope.launch {
            try {
                // userId 由 Repository 自动填入当前登录用户
                repository.insert(transaction)
                Toast.makeText(this@VoiceInputActivity, "语音记账已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@VoiceInputActivity, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    /** 页面是否仍可用（防止后台线程回调时操作已销毁的界面） */
    private fun isActive(): Boolean = !isFinishing && !isDestroyed

    override fun onDestroy() {
        super.onDestroy()
        Log.e("VoskDebug", "onDestroy: 停止录音并释放资源")
        isRecording = false
        // 先等录音线程退出，避免与 Vosk native 层并发导致 use-after-free 闪退
        try {
            recordingThread?.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        vosk.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
