package com.example.myapplication.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Utility class to manage application preferences
 */
class PreferencesManager(context: Context) {

    companion object {
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val DEFAULT_SERVER_ADDRESS = "http://10.0.2.2:5000/"
        private const val KEY_SCAN_BUTTON_KEYCODE = "scan_button_keycode"
        private const val KEY_SCAN_BUTTON_NAME = "scan_button_name"
        private const val KEY_BARCODE_APPCODE = "barcode_appcode"
        
        @Volatile
        private var instance: PreferencesManager? = null
        
        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    /**
     * Get the saved server address or return the default one
     */
    fun getServerAddress(): String {
        return preferences.getString(KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS) ?: DEFAULT_SERVER_ADDRESS
    }
    
    /**
     * Save a new server address
     */
    fun saveServerAddress(address: String) {
        preferences.edit().putString(KEY_SERVER_ADDRESS, address).apply()
    }
    
    /**
     * Save a keycode binding for the scan button
     */
    fun saveScanButtonKeyCode(keyCode: Int, buttonName: String) {
        preferences.edit()
            .putInt(KEY_SCAN_BUTTON_KEYCODE, keyCode)
            .putString(KEY_SCAN_BUTTON_NAME, buttonName)
            .apply()
    }
    
    /**
     * Get the keycode bound to the scan button, or null if none is bound
     */
    fun getScanButtonKeyCode(): Int? {
        val keyCode = preferences.getInt(KEY_SCAN_BUTTON_KEYCODE, -1)
        return if (keyCode != -1) keyCode else null
    }
    
    /**
     * Get the name of the button bound to scan, or null if none is bound
     */
    fun getScanButtonName(): String? {
        return preferences.getString(KEY_SCAN_BUTTON_NAME, null)
    }
    
    /**
     * Clear any existing scan button binding
     */
    fun clearScanButtonBinding() {
        preferences.edit()
            .remove(KEY_SCAN_BUTTON_KEYCODE)
            .remove(KEY_SCAN_BUTTON_NAME)
            .apply()
    }
    
    /**
     * Save the APPCODE for the barcode API
     */
    fun saveBarcodeAppCode(appCode: String) {
        preferences.edit().putString(KEY_BARCODE_APPCODE, appCode).apply()
    }
    
    /**
     * Get the saved APPCODE for the barcode API
     */
    fun getBarcodeAppCode(): String? {
        return preferences.getString(KEY_BARCODE_APPCODE, null)
    }
} 