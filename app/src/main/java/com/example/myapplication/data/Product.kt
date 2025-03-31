package com.example.myapplication.data

import com.google.gson.annotations.SerializedName

data class Product(
    val code: String,
    val name: String,
    @SerializedName("specs") val description: String,
    @SerializedName("stock") val quantity: Int? = null,
    @SerializedName("location_code") val location: String? = null,
    val cost: Float? = null,
    val link: String? = null
)

data class ProductResponse(
    val product: Product,
    val success: Boolean
)

data class ApiResponse(
    val status: String,
    val message: String,
    val data: Any? = null
)

data class InStockRequest(
    val code: String,
    val quantity: Int = 1,
    @SerializedName("location_code") val location: String? = null
)

data class OutStockRequest(
    val code: String,
    val quantity: Int = 1
)

data class MoveStockRequest(
    val code: String,
    @SerializedName("location_code") val location: String
)

/**
 * Data class for sending product images as base64 encoded strings
 * The request accepts any number of images with keys in the format "image1", "image2", etc.
 */
data class ProductImagesUploadRequest(
    val code: String,
    val images: Map<String, String> // Map of "image1", "image2", etc. to base64 strings
) {
    companion object {
        // Creates a request from a product code and a list of base64 image strings
        fun create(code: String, base64Images: List<String>): ProductImagesUploadRequest {
            val imagesMap = mutableMapOf<String, String>()
            base64Images.forEachIndexed { index, base64 ->
                imagesMap["image${index + 1}"] = base64
            }
            return ProductImagesUploadRequest(code, imagesMap)
        }
    }
} 