package com.example.myapplication.api

import com.example.myapplication.data.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface StockSyncApi {
    @Suppress("unused")
    @GET("api/health")
    suspend fun getHealth(): Response<ApiResponse>

    @Suppress("unused")
    @GET("api/products")
    suspend fun getAllProducts(): Response<List<Product>>

    @GET("api/products")
    suspend fun getProductByCode(@Query("code") code: String): Response<ProductResponse>

    @GET("api/products")
    suspend fun getProductByBarcode(@Query("barcode") barcode: String): Response<ProductResponse>

    @Suppress("unused")
    @GET("api/product/{code}")
    suspend fun getProduct(@Path("code") code: String): Response<ProductResponse>

    @POST("api/instock")
    suspend fun addStock(@Body request: InStockRequest): Response<ApiResponse>

    @POST("api/outstock")
    suspend fun removeStock(@Body request: OutStockRequest): Response<ApiResponse>

    @POST("api/move-stock")
    suspend fun changeProductLocation(@Body request: MoveStockRequest): Response<ApiResponse>

    @POST("api/add-product")
    suspend fun addProduct(@Body product: Product): Response<ApiResponse>
    
    /**
     * Add or update a barcode for a product
     */
    @POST("api/scan-barcode")
    suspend fun addBarcode(@Body request: BarcodeRequest): Response<BarcodeResponse>
    
    /**
     * Upload product images using base64 encoded strings in a JSON payload
     */
    @POST("api/upload-multiple-images")
    suspend fun uploadProductImages(
        @Body request: Map<String, String>
    ): Response<ApiResponse>
} 