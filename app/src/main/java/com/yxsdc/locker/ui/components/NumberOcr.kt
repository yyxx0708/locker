package com.yxsdc.locker.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.yxsdc.locker.data.DailyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 离线 OCR：识别图片中的文字。ML Kit 拉丁模型随 APK 内置，
 * 不需要网络，也不依赖 Google Play 服务。
 */

/** 进程内共享的识别器，避免每次识别都重新初始化模型。 */
private val sharedRecognizer: com.google.mlkit.vision.text.TextRecognizer by lazy {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
}

/** ML Kit 识别器串行处理，避免并发调用。 */
private val recognizerMutex = Mutex()

suspend fun recognizeText(bitmap: Bitmap): String = recognizerMutex.withLock {
    suspendCancellableCoroutine { continuation ->
        sharedRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                if (continuation.isActive) {
                    continuation.resume(text.text)
                }
            }
            .addOnFailureListener { exception ->
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
    }
}

/** OCR 候选缓存：同一张照片只识别一次（日报、上传检测、手动识别共用）。 */
private val numberCandidatesCache = LruCache<String, List<String>>(32)

/**
 * 识别照片中的 5 位号码候选，按 JPEG 内容指纹缓存；解码与识别都在 IO 线程完成。
 * 照片未上传、识别失败时返回空列表。
 */
suspend fun recognizeNumberCandidates(jpeg: ByteArray): List<String> =
    withContext(Dispatchers.IO) {
        val key = DailyReport.photoFingerprint(jpeg)
        numberCandidatesCache.get(key)?.let { return@withContext it }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return@withContext emptyList()
        try {
            val candidates = DailyReport.findFiveDigitCandidates(recognizeText(bitmap))
            numberCandidatesCache.put(key, candidates)
            candidates
        } finally {
            bitmap.recycle()
        }
    }
