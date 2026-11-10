package com.v2ray.ang.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

/**
 * QR code decoder utility.
 */
object QRCodeDecoder {
    // Keep decoding hints focused on QR codes and enable TRY_HARDER + UTF-8 charset for a
    // better success rate.
    private val HINTS: MutableMap<DecodeHintType, Any?> = EnumMap<DecodeHintType, Any?>(DecodeHintType::class.java).apply {
        put(DecodeHintType.TRY_HARDER, true)
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        put(DecodeHintType.CHARACTER_SET, Charsets.UTF_8.name())
    }

    /**
     * Creates a QR code bitmap from the given text.
     *
     * @param text The text to encode in the QR code.
     * @param size The size of the QR code bitmap.
     * @return The generated QR code bitmap, or null if an error occurs.
     */
    fun createQRCode(text: String, size: Int = 800): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8)
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size) { i ->
                if (bitMatrix.get(i % size, i / size)) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        }.getOrNull()
    }

    /**
     * Decodes a QR code from a local image file. This method is time-consuming and should be called in a background thread.
     *
     * @param picturePath The local path of the image file to decode.
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(picturePath: String): String? = syncDecodeQRCode(getDecodeAbleBitmap(picturePath))

    /**
     * Decodes a QR code from a bitmap. This method is time-consuming and should be called in a background thread.
     *
     * @param bitmap The bitmap to decode.
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(bitmap: Bitmap?): String? = bitmap?.let {
        runCatching {
            val pixels = IntArray(it.width * it.height).also { array ->
                it.getPixels(array, 0, it.width, 0, 0, it.width, it.height)
            }
            val source = RGBLuminanceSource(it.width, it.height, pixels)
            val qrReader = QRCodeReader()

            try {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), HINTS).text
            } catch (_: NotFoundException) {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())), HINTS).text
            }
        }.getOrNull()
    }

    /**
     * Converts a local image file to a bitmap that can be decoded as a QR code. The image is compressed to avoid being too large.
     *
     * @param picturePath The local path of the image file.
     * @return The decoded bitmap, or null if an error occurs.
     */
    private fun getDecodeAbleBitmap(picturePath: String): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(picturePath, options)
        options.inSampleSize = (options.outHeight / 400).coerceAtLeast(1)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeFile(picturePath, options)
    }.getOrNull()
}
