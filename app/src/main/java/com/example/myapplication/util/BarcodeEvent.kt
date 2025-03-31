package com.example.myapplication.util

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Singleton for handling barcode events throughout the app
 * Using LiveData for lifecycle-aware event delivery
 */
object BarcodeEvent {
    // Constant for event naming
    const val BARCODE_RESULT = "barcode_result"
    
    // Private mutable live data for internal modification
    private val _barcodeData = MutableLiveData<String>()
    
    // Public immutable live data for observing
    val barcodeData: LiveData<String> = _barcodeData
    
    // Store the last received barcode for deduplication
    private var lastBarcode: String? = null
    
    /**
     * Post a new barcode scan result to all observers
     * Only processes if the barcode is different from the last one received
     */
    fun postBarcodeResult(barcode: String) {
        // Don't post the same barcode twice in a row
        if (barcode != lastBarcode) {
            lastBarcode = barcode
            _barcodeData.postValue(barcode)
        }
    }
    
    /**
     * Reset the barcode data
     */
    fun reset() {
        lastBarcode = null
        _barcodeData.postValue("")
    }
    
    /**
     * Call this when switching between fragments to avoid
     * processing a barcode in multiple fragments
     */
    fun clearLastBarcode() {
        lastBarcode = null
        
        // Also clear the LiveData value to prevent it from
        // being delivered to new observers in other fragments
        _barcodeData.postValue("")
    }
} 