package com.example.myapplication.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Utility class for image operations
 */
object ImageUtil {
    private const val TAG = "ImageUtil"
    private const val MAX_IMAGE_DIMENSION = 1200 // Max dimension for resize
    private const val JPEG_QUALITY = 85 // Quality for JPEG compression
    
    /**
     * Convert a file to a base64 encoded string with data URI prefix
     * @param file The image file to convert
     * @return Base64 encoded string with data URI prefix or null if conversion fails
     */
    fun fileToBase64DataUri(file: File): String? {
        return try {
            // Load and optionally resize the bitmap
            val bitmap = loadAndResizeBitmap(file.absolutePath)
            
            // Convert to byte array
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            
            // Convert to base64
            val base64 = Base64.encodeToString(byteArray, Base64.DEFAULT)
            
            // Add data URI prefix
            "data:image/jpeg;base64,$base64"
        } catch (e: IOException) {
            Log.e(TAG, "Error converting file to base64: ${e.message}", e)
            null
        }
    }
    
    /**
     * Load a bitmap from a file and resize it if needed to save memory
     */
    private fun loadAndResizeBitmap(imagePath: String): Bitmap {
        // First decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(imagePath, options)
        
        // Calculate inSampleSize to resize the image
        options.inSampleSize = calculateInSampleSize(options, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
        
        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(imagePath, options)
    }
    
    /**
     * Calculate the sample size for downsampling an image
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
} 