package com.hydro.link.server.ime

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HydroLink 输入法服务与手写汉字识别引擎。
 *
 * 优先使用 Google ML Kit Digital Ink Recognition 离线手写模型进行高精度识别；
 * 当模型未就绪或识别失败时，自动切换至轻量级笔画特征拓扑匹配引擎兜底。
 */
object ImeManager {

    private const val TAG = "ImeManager"
    @Volatile
    private var recognizer: DigitalInkRecognizer? = null
    @Volatile
    private var recognitionModel: DigitalInkRecognitionModel? = null
    private val isModelReady = AtomicBoolean(false)
    private val isDownloading = AtomicBoolean(false)

    /**
     * 初始化输入法引擎（后台异步检查并加载手写模型）。
     */
    fun init(context: Context) {
        try {
            val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hans")
                ?: DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani")
                ?: run {
                    Log.w(TAG, "未找到 zh-Hans / zh-Hani 手写模型标识")
                    return
                }

            val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
            recognitionModel = model
            recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )

            checkOrDownloadModel()
        } catch (e: Throwable) {
            Log.e(TAG, "初始化 ML Kit Digital Ink 失败", e)
        }
    }

    /**
     * 检查手写模型是否就绪；若未就绪则在后台触发下载。
     */
    fun checkOrDownloadModel() {
        val model = recognitionModel ?: return
        val remoteModelManager = RemoteModelManager.getInstance()

        remoteModelManager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    isModelReady.set(true)
                    Log.i(TAG, "ML Kit 中文手写模型已就绪 (离线可用)")
                } else {
                    isModelReady.set(false)
                    if (isDownloading.compareAndSet(false, true)) {
                        Log.i(TAG, "开始下载 ML Kit 中文手写模型...")
                        remoteModelManager.download(model, DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                isModelReady.set(true)
                                isDownloading.set(false)
                                Log.i(TAG, "ML Kit 中文手写模型下载完成")
                            }
                            .addOnFailureListener { e ->
                                isDownloading.set(false)
                                Log.w(TAG, "下载 ML Kit 手写模型失败（将使用内置引擎兜底）: ${e.message}")
                            }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "检查 ML Kit 模型状态失败: ${e.message}")
            }
    }

    /**
     * 识别手写笔画。
     *
     * @param bodyJson 包含 strokes: [[[x, y], ...], ...], width, height, max 的请求体
     * @return 识别出的候选字列表（Top-N）
     */
    suspend fun recognize(bodyJson: JsonObject?): List<String> = withContext(Dispatchers.IO) {
        if (bodyJson == null) return@withContext emptyList()

        val strokesArray = bodyJson.getAsJsonArray("strokes")
            ?: return@withContext emptyList()
        if (strokesArray.isEmpty) return@withContext emptyList()

        val maxCandidates = bodyJson.get("max")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 20) ?: 8

        // 1. 构建点集与笔画
        val strokes = parseStrokes(strokesArray)
        if (strokes.isEmpty() || strokes.all { it.isEmpty() }) {
            return@withContext emptyList()
        }

        // 2. 尝试使用 ML Kit Recognizer
        val rec = recognizer
        if (isModelReady.get() && rec != null) {
            try {
                val ink = buildInk(strokes)
                val task = rec.recognize(ink)
                val result: RecognitionResult = Tasks.await(task, 3000, TimeUnit.MILLISECONDS)
                val candidates = result.candidates.map { it.text }.filter { it.isNotBlank() }
                if (candidates.isNotEmpty()) {
                    return@withContext candidates.take(maxCandidates)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ML Kit 手写识别异常，降级至特征拓扑匹配: ${e.message}")
            }
        }

        // 3. Fallback: 使用轻量笔画拓扑匹配算法
        FallbackHandwritingEngine.recognize(strokes, maxCandidates)
    }

    private fun parseStrokes(strokesArray: JsonArray): List<List<Pair<Float, Float>>> {
        val result = mutableListOf<List<Pair<Float, Float>>>()
        for (i in 0 until strokesArray.size()) {
            val strokeEl = strokesArray.get(i)
            if (!strokeEl.isJsonArray) continue
            val strokeArr = strokeEl.asJsonArray
            val points = mutableListOf<Pair<Float, Float>>()
            for (j in 0 until strokeArr.size()) {
                val ptEl = strokeArr.get(j)
                if (!ptEl.isJsonArray) continue
                val ptArr = ptEl.asJsonArray
                if (ptArr.size() >= 2) {
                    val x = ptArr.get(0).asFloat
                    val y = ptArr.get(1).asFloat
                    points.add(x to y)
                }
            }
            if (points.isNotEmpty()) {
                result.add(points)
            }
        }
        return result
    }

    private fun buildInk(strokes: List<List<Pair<Float, Float>>>): Ink {
        val inkBuilder = Ink.builder()
        var timestamp = 0L
        for (stroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            for ((x, y) in stroke) {
                strokeBuilder.addPoint(Ink.Point.create(x, y, timestamp))
                timestamp += 20L // 模拟递增时间戳，辅助识别时序特征
            }
            inkBuilder.addStroke(strokeBuilder.build())
            timestamp += 100L
        }
        return inkBuilder.build()
    }
}
