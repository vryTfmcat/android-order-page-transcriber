package com.timedirection.ordercapture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object OcrEngine {
    data class ResultText(val text: String, val lowConfidenceSegments: Int, val discardedLines: Int)

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(bitmap: Bitmap, callback: (Result<ResultText>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                var uncertain = 0
                var discarded = 0
                val segments = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val confidences = line.elements.map { it.confidence }.filter { it >= 0f }
                        val averageConfidence = confidences.takeIf { it.isNotEmpty() }?.average() ?: 1.0
                        val lowRatio = confidences.takeIf { it.isNotEmpty() }
                            ?.count { it < 0.45f }
                            ?.toDouble()
                            ?.div(confidences.size) ?: 0.0
                        if (averageConfidence < 0.38 && lowRatio >= 0.5) {
                            discarded += 1
                            return@mapNotNull null
                        }
                        var value = line.text
                        line.elements.filter { element ->
                            element.confidence in 0f..<0.35f &&
                                (element.text.any(Char::isDigit) || element.text.any { it == '¥' || it == '￥' })
                        }.forEach { element ->
                            value = value.replaceFirst(element.text, "〔${element.text}·待核对〕")
                            uncertain += 1
                        }
                        val bounds = line.boundingBox ?: return@mapNotNull null
                        OcrReadingOrder.Segment(value, bounds.left, bounds.top, bounds.right, bounds.bottom)
                    }
                }
                val marked = OcrReadingOrder.assemble(segments).ifBlank { result.text.trim() }
                callback(Result.success(ResultText(marked, uncertain, discarded)))
            }
            .addOnFailureListener { error -> callback(Result.failure(error)) }
    }
}
