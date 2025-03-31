package com.example.myapplication.api

import android.content.Context
import android.util.Log
import com.example.myapplication.util.PreferencesManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val TAG = "NetworkModule"
    
    // Will be initialized with the actual base URL from preferences
    private var BASE_URL = "http://10.0.2.2:5000/"

    private val gson: Gson by lazy {
        GsonBuilder().create()
    }

    private val httpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS) // Reduced timeout for faster feedback
            .readTimeout(15, TimeUnit.SECONDS)    // Reduced timeout for faster feedback
            .build()
    }

    private var retrofit: Retrofit? = null
    private var stockSyncApiInstance: StockSyncApi? = null

    fun initialize(context: Context) {
        val previousUrl = BASE_URL
        BASE_URL = PreferencesManager.getInstance(context).getServerAddress()
        
        // Log the server address being used
        Log.d(TAG, "Initializing NetworkModule with server address: $BASE_URL")
        
        if (previousUrl != BASE_URL) {
            Log.d(TAG, "Server address changed from $previousUrl to $BASE_URL")
            // Reset the retrofit instance to use the new BASE_URL
            retrofit = null
            stockSyncApiInstance = null
        }
    }

    val stockSyncApi: StockSyncApi
        get() {
            if (stockSyncApiInstance == null) {
                stockSyncApiInstance = getRetrofit().create(StockSyncApi::class.java)
            }
            return stockSyncApiInstance!!
        }

    private fun getRetrofit(): Retrofit {
        if (retrofit == null) {
            Log.d(TAG, "Creating new Retrofit instance with base URL: $BASE_URL")
            retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(httpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
        }
        return retrofit!!
    }
} 