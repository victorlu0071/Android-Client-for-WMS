package com.example.myapplication.data

import com.google.gson.annotations.SerializedName

data class Product(
    // For new products, code can be null (server-generated)
    val code: String? = null,
    val name: String,
    @SerializedName("specs") val description: String,
    @SerializedName("stock") val quantity: Int? = null,
    @SerializedName("location_code") val location: String? = null,
    val cost: Float? = null,
    val link: String? = null,
    // For new products, barcode can be provided as identifier
    @SerializedName("barcode") val barcode: String? = null
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
    val code: String? = null,
    val barcode: String? = null,
    val quantity: Int = 1,
    @SerializedName("location_code") val location: String? = null
) {
    // Validate that either code or barcode is provided
    init {
        require(code != null || barcode != null) { "Either code or barcode must be provided" }
    }
}

data class OutStockRequest(
    val code: String? = null,
    val barcode: String? = null,
    val quantity: Int = 1
) {
    // Validate that either code or barcode is provided
    init {
        require(code != null || barcode != null) { "Either code or barcode must be provided" }
    }
}

data class MoveStockRequest(
    val code: String? = null,
    val barcode: String? = null,
    @SerializedName("location_code") val location: String
) {
    // Validate that either code or barcode is provided
    init {
        require(code != null || barcode != null) { "Either code or barcode must be provided" }
    }
}

/**
 * Data class for adding or updating a barcode for a product
 */
data class BarcodeRequest(
    val code: String,      // Product code
    @SerializedName("barcode") val barcode: String    // Barcode value (must start with "69" and be 13 digits)
)

/**
 * Response for barcode operations
 */
data class BarcodeResponse(
    val success: Boolean,
    val message: String,
    @SerializedName("product_code") val productCode: String,
    @SerializedName("barcode") val barcode: String
)

/**
 * Response for image upload operations
 */
data class ImageUploadResponse(
    val success: Boolean,
    val message: String,
    @SerializedName("uploaded_images") val uploadedImages: List<UploadedImage>
)

/**
 * Represents a single uploaded image in the response
 */
data class UploadedImage(
    val index: Int,
    val path: String
)

/**
 * Data class for sending product images as base64 encoded strings
 * The request accepts any number of images with keys in the format "image1", "image2", etc.
 * @deprecated Use multipart form data instead
 */
@Deprecated("Use multipart form data instead with the new uploadProductImages API")
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