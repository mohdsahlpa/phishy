package com.test.phishy

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DomainClassifier(context: Context) {

    private val interpreter: Interpreter

    companion object {
        private const val MODEL_PATH = "phishing_url_model.tflite"
        // The model expects input of this specific length.
        private const val MAX_DOMAIN_LENGTH = 100
        // A map to convert characters to their numerical representation for the model.
        private val CHAR_MAP = "abcdefghijklmnopqrstuvwxyz0123456789.-_".associateWith { it.code.toFloat() }
    }

    init {
        val model = loadModelFile(context)
        interpreter = Interpreter(model)
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_PATH)
        val fileInputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun isPhishing(domain: String): Boolean {
        // Preprocess the domain string into the format the model expects.
        val processedInput = preprocess(domain)

        // ✅ FIX: The output array shape is now [33, 1] to match the model's actual output.
        val output = Array(33) { FloatArray(1) }
        interpreter.run(processedInput, output)

        // The model's prediction is the single value in the first row of the output array.
        // A value > 0.5 is considered "phishing".
        return output[0][0] > 0.5f
    }

    private fun preprocess(domain: String): Array<FloatArray> {
        val normalizedDomain = domain.lowercase()
        val input = FloatArray(MAX_DOMAIN_LENGTH)

        // Convert the domain string to a sequence of floats based on the CHAR_MAP.
        for (i in 0 until MAX_DOMAIN_LENGTH) {
            if (i < normalizedDomain.length) {
                // Use the character's float value or 0.0 if it's not in the map.
                input[i] = CHAR_MAP[normalizedDomain[i]] ?: 0.0f
            } else {
                // Pad the rest of the input with zeros.
                input[i] = 0.0f
            }
        }
        // The interpreter expects the input to be wrapped in an array.
        return arrayOf(input)
    }
}