package com.timedirection.ordercapture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object OcrEngine {
    data class ResultText(val text: String, val lowConfidenceSegments: Int)

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(bitmap: Bitmap, callback: (Result<ResultText>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                var uncertain = 0
                val marked = result.textBlocks.joinToString("\n") { block ->
                    block.lines.joinToString("\n") { line ->
                        var value = line.text
                        line.elements.filter { it.confidence in 0f..<0.65f }.forEach { element ->
                            value = value.replaceFirst(element.text, "〔${element.text}·待核对〕")
                            uncertain += 1
                        }
                        value
                    }
                }.trim()
                callback(Result.success(ResultText(marked, uncertain)))
            }
            .addOnFailureListener { error -> callback(Result.failure(error)) }
    }
}
