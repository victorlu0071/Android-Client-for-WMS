package com.example.myapplication.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.myapplication.R
import com.example.myapplication.api.NetworkModule
import com.example.myapplication.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var preferencesManager: PreferencesManager
    private var bindScanButtonDialog: AlertDialog? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        
        preferencesManager = PreferencesManager.getInstance(requireContext())
        
        setupServerAddressPreference()
        setupButtonBindingPreferences()
        setupBarcodeApiPreference()
    }
    
    private fun setupServerAddressPreference() {
        // Get reference to the server address preference and test connection preference
        val serverAddressPref = findPreference<EditTextPreference>("server_address")
        val testConnectionPref = findPreference<Preference>("test_connection")
        
        // Set initial value from preferences manager
        val currentAddress = preferencesManager.getServerAddress()
        
        // Update summary to show the actual server address
        serverAddressPref?.summary = "Current: $currentAddress"
        
        // Configure input type for URL entry
        serverAddressPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // Set current value to make editing easier
            editText.setText(currentAddress)
        }
        
        // Listen for changes to the server address preference
        serverAddressPref?.setOnPreferenceChangeListener { _, newValue ->
            val serverAddress = newValue.toString()
            
            // Validate server address
            if (isValidServerAddress(serverAddress)) {
                // Update the preference summary
                serverAddressPref.summary = "Current: $serverAddress"
                
                // Save the new server address
                preferencesManager.saveServerAddress(serverAddress)
                
                // Reinitialize the NetworkModule with the new address
                NetworkModule.initialize(requireContext())
                
                Toast.makeText(requireContext(), "Server address updated", Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(requireContext(), "Invalid server address", Toast.LENGTH_SHORT).show()
                false
            }
        }
        
        // Set up the test connection button
        testConnectionPref?.setOnPreferenceClickListener {
            testApiConnection(preferencesManager.getServerAddress())
            true
        }
    }
    
    private fun setupButtonBindingPreferences() {
        val bindScanButtonPref = findPreference<Preference>("bind_scan_button")
        val clearButtonBindingPref = findPreference<Preference>("clear_button_binding")
        
        // Set initial summary based on current binding
        updateBindButtonSummary(bindScanButtonPref)
        
        // Set up click listener for bind button preference
        bindScanButtonPref?.setOnPreferenceClickListener {
            showBindButtonDialog()
            true
        }
        
        // Set up click listener for clear binding preference
        clearButtonBindingPref?.setOnPreferenceClickListener {
            preferencesManager.clearScanButtonBinding()
            updateBindButtonSummary(bindScanButtonPref)
            Toast.makeText(requireContext(), "Button binding cleared", Toast.LENGTH_SHORT).show()
            true
        }
    }
    
    private fun updateBindButtonSummary(bindScanButtonPref: Preference?) {
        val buttonName = preferencesManager.getScanButtonName()
        bindScanButtonPref?.summary = if (buttonName != null) {
            getString(R.string.settings_bind_scan_button_current, buttonName)
        } else {
            getString(R.string.settings_bind_scan_button_summary)
        }
    }
    
    private fun showBindButtonDialog() {
        // Create a custom dialog to capture key events
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_bind_button, null)
        
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_bind_scan_button_dialog_title)
            .setMessage(R.string.settings_bind_scan_button_dialog_message)
            .setView(dialogView)
            .setCancelable(true)
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()
        
        // Set the dialog to handle key events
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && 
                keyCode != KeyEvent.KEYCODE_BACK && 
                keyCode != KeyEvent.KEYCODE_MENU) {
                
                // Get the button name from the key event
                val buttonName = getKeyName(keyCode)
                
                // Save the button binding
                preferencesManager.saveScanButtonKeyCode(keyCode, buttonName)
                
                // Show success message
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_bind_scan_button_success, buttonName),
                    Toast.LENGTH_SHORT
                ).show()
                
                // Update the preference summary
                updateBindButtonSummary(findPreference("bind_scan_button"))
                
                // Dismiss the dialog
                dialog.dismiss()
                return@setOnKeyListener true
            }
            false
        }
        
        dialog.show()
        bindScanButtonDialog = dialog
    }
    
    private fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume Up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume Down"
            KeyEvent.KEYCODE_CAMERA -> "Camera Button"
            KeyEvent.KEYCODE_FOCUS -> "Focus Button"
            KeyEvent.KEYCODE_SEARCH -> "Search Button"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Media Play/Pause"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Media Next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Media Previous"
            else -> "Key Code: $keyCode"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Dismiss dialog if it's showing to prevent window leaks
        bindScanButtonDialog?.dismiss()
        bindScanButtonDialog = null
    }
    
    private fun testApiConnection(serverAddress: String) {
        // First check if we have an internet connection
        if (!isNetworkAvailable()) {
            Toast.makeText(
                requireContext(),
                "No network connection available",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        
        Toast.makeText(
            requireContext(),
            "Testing connection to $serverAddress...",
            Toast.LENGTH_SHORT
        ).show()
        
        // Test the connection in a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${serverAddress}api/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                
                withContext(Dispatchers.Main) {
                    if (responseCode == 200) {
                        Toast.makeText(
                            requireContext(),
                            "Connection successful! Server is reachable.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Connection failed with response code: $responseCode",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Connection failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            else -> false
        }
    }
    
    private fun isValidServerAddress(address: String): Boolean {
        // Simple validation to ensure it's not empty and has a schema
        return address.isNotEmpty() && 
               (address.startsWith("http://") || address.startsWith("https://")) &&
               address.endsWith("/")
    }
    
    private fun setupBarcodeApiPreference() {
        val barcodeAppCodePref = findPreference<EditTextPreference>("barcode_appcode")
        val barcodeApiHelpPref = findPreference<Preference>("barcode_api_help")
        
        // Set initial value from preferences manager
        val currentAppCode = preferencesManager.getBarcodeAppCode()
        
        // Update summary to show if an APPCODE is set, but hide the actual value for security
        barcodeAppCodePref?.summary = if (currentAppCode.isNullOrEmpty()) {
            "Set your Ali Barcode API APPCODE for product lookups"
        } else {
            "APPCODE is set (tap to change)"
        }
        
        // Configure input type for text entry
        barcodeAppCodePref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT
            // Allow editing from scratch rather than showing existing value
            editText.setText(currentAppCode)
        }
        
        // Listen for changes to the APPCODE preference
        barcodeAppCodePref?.setOnPreferenceChangeListener { _, newValue ->
            val appCode = newValue.toString().trim()
            
            // Save the new APPCODE
            preferencesManager.saveBarcodeAppCode(appCode)
            
            // Update the preference summary
            barcodeAppCodePref.summary = if (appCode.isEmpty()) {
                "Set your Ali Barcode API APPCODE for product lookups"
            } else {
                "APPCODE is set (tap to change)"
            }
            
            Toast.makeText(requireContext(), "Barcode API APPCODE updated", Toast.LENGTH_SHORT).show()
            true
        }
        
        // Set up the help link
        barcodeApiHelpPref?.setOnPreferenceClickListener {
            Toast.makeText(
                requireContext(),
                "The APPCODE is required for automatic product lookup when scanning barcodes starting with '69'",
                Toast.LENGTH_LONG
            ).show()
            true
        }
    }
}