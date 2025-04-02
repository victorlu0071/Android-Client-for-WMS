package com.example.myapplication.ui.productinfo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.api.NetworkModule
import com.example.myapplication.data.BarcodeRequest
import com.example.myapplication.data.BarcodeResponse
import com.example.myapplication.data.Product
import com.example.myapplication.databinding.FragmentProductInfoBinding
import com.example.myapplication.ui.scanner.CustomBarcodeScannerActivity
import com.example.myapplication.util.BarcodeEvent
import com.example.myapplication.util.PreferencesManager
import com.example.myapplication.util.SoundUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProductInfoFragment : Fragment() {
    private val tag = "ProductInfoFragment"
    private var _binding: FragmentProductInfoBinding? = null
    private val binding get() = _binding!!
    
    // Track the last processed barcode to prevent duplicate processing
    private var lastProcessedBarcode: String? = null
    private var fetchJob: Job? = null
    private var isProcessingBarcode = false
    
    // Store the last displayed product to persist between tab changes
    private var lastDisplayedProduct: Product? = null
    
    // Track if the current search was performed using a barcode
    private var searchedByBarcode = false
    
    // Register for the barcode scanner activity result
    private val scanBarcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val barcodeValue = data?.getStringExtra(CustomBarcodeScannerActivity.BARCODE_RESULT)
            
            if (!barcodeValue.isNullOrEmpty()) {
                handleScannedBarcode(barcodeValue)
            }
        }
    }
    
    private fun processBarcodeResult(barcodeValue: String) {
        // When processing a barcode right after returning to this fragment
        // we want to process it even if it's the same as before
        val isSameAsPrevious = barcodeValue == lastProcessedBarcode
        
        // Check if we've already processed this barcode recently in the same session
        if (isSameAsPrevious && lastDisplayedProduct != null && binding.productDetailsCard.isVisible) {
            Log.d(tag, "Ignoring duplicate barcode in the same session: $barcodeValue")
            return
        }
        
        isProcessingBarcode = true
        lastProcessedBarcode = barcodeValue
        
        // Set the scanned barcode value to the input field
        binding.productCodeInput.setText(barcodeValue)
        
        // Cancel any existing fetch job
        fetchJob?.cancel()
        
        // Check if this is a barcode format
        val isBarcode = barcodeValue.startsWith("69") && barcodeValue.length == 13 && barcodeValue.all { it.isDigit() }
        searchedByBarcode = isBarcode
        
        // Automatically search for the product
        fetchProductDetails(barcodeValue)
        
        // Reset processing flag after a delay
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            isProcessingBarcode = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(tag, "ProductInfoFragment onViewCreated - initializing UI")

        // Update input hint to show users they can enter either code or barcode
        binding.productCodeInputLayout.hint = getString(R.string.product_search_hint) + " or barcode"

        // Make the barcode input field request focus when touched
        binding.barcodeInput.setOnClickListener {
            binding.barcodeInput.requestFocus()
            Log.d(tag, "Barcode input field clicked, requesting focus")
        }
        
        // Add double-tap to clear functionality for the barcode input
        binding.barcodeInput.setOnLongClickListener {
            if (!binding.barcodeInput.text.isNullOrEmpty()) {
                binding.barcodeInput.setText("")
                Toast.makeText(requireContext(), getString(R.string.barcode_field_cleared), Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            return@setOnLongClickListener false // Let the default long click handler run
        }
        
        // Make the input field show an on-screen keyboard when focused
        binding.barcodeInput.setOnFocusChangeListener { _, hasFocus ->
            Log.d(tag, "Barcode input focus changed: hasFocus=$hasFocus")
            
            // Change the background color slightly when focused to make it more obvious
            if (hasFocus) {
                binding.barcodeInput.setBackgroundColor(android.graphics.Color.parseColor("#F3E5F5"))
            } else {
                binding.barcodeInput.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        
        // Add clear button to barcode input layout
        binding.barcodeInputLayout.setEndIconOnClickListener {
            binding.barcodeInput.text?.clear()
            binding.barcodeInput.requestFocus()
        }
        binding.barcodeInputLayout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
        
        // Search button click listener
        binding.searchButton.setOnClickListener {
            val input = binding.productCodeInput.text.toString().trim()
            if (input.isNotEmpty()) {
                // Reset processing flags when manually searching
                isProcessingBarcode = false
                lastProcessedBarcode = null
                Log.d(tag, "Search button clicked - searching for: $input")
                fetchProductDetails(input)
            } else {
                Toast.makeText(requireContext(), getString(R.string.please_enter_product_code_search), Toast.LENGTH_SHORT).show()
            }
        }
        
        // Add text watcher to enable save button when valid barcode is entered
        binding.barcodeInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateBarcode(s.toString())
            }
        })
        
        // Save barcode button
        binding.saveBarcodeButton.setOnClickListener {
            saveProductBarcode()
        }
        
        // Add an explicit focus handler for the barcode input
        binding.barcodeSectionCard.setOnClickListener {
            // When user taps anywhere in the barcode section, give focus to the input
            binding.barcodeInput.requestFocus()
            Log.d(tag, "Barcode section card clicked, requesting focus for input field")
        }
        
        // Make the barcode input field visually indicate it has focus
        binding.barcodeInput.setOnFocusChangeListener { _, hasFocus ->
            Log.d(tag, "Barcode input focus changed: hasFocus=$hasFocus")
            
            // Change the background color slightly when focused to make it more obvious
            if (hasFocus) {
                binding.barcodeInput.setBackgroundColor(android.graphics.Color.parseColor("#F3E5F5"))
            } else {
                binding.barcodeInput.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        
        // Observe barcode events using LiveData - completely rewritten
        BarcodeEvent.barcodeData.observe(viewLifecycleOwner) { barcode ->
            // Only process non-empty barcodes when not already processing
            if (!barcode.isNullOrEmpty() && barcode.trim().isNotEmpty()) {
                Log.d(tag, "BarcodeEvent received: $barcode, isProcessing: $isProcessingBarcode")
                
                if (!isProcessingBarcode) {
                    // Set flag to prevent duplicate processing
                    isProcessingBarcode = true
                    Log.d(tag, "Starting to process barcode: $barcode")
                    
                    try {
                        // Only play a sound if one hasn't been played already for this barcode
                        if (!BarcodeEvent.hasSoundBeenPlayed()) {
                            Log.d(tag, "Playing sound for barcode: $barcode")
                            SoundUtil.playScanBeep()
                            BarcodeEvent.markSoundPlayed()
                        } else {
                            Log.d(tag, "Sound already played for barcode: $barcode")
                        }
                        
                        // Process the barcode based on focus - if the barcode was handled by an input field,
                        // don't continue with further processing
                        val handled = handleScannedBarcode(barcode)
                        
                        Log.d(tag, "Barcode $barcode handled by input field: $handled")
                        
                        // Very important - clear the barcode from the event
                        BarcodeEvent.clearLastBarcode()
                        Log.d(tag, "Cleared barcode after processing")
                    } finally {
                        // Reset processing flag after a delay to prevent immediate reprocessing
                        view.postDelayed({
                            isProcessingBarcode = false
                            Log.d(tag, "Reset processing flag")
                        }, 1000)
                    }
                } else {
                    Log.d(tag, "Ignoring barcode event - already processing another barcode")
                }
            }
        }
        
        // Restore the last product if available
        lastDisplayedProduct?.let { product ->
            displayProductDetails(product)
            // Make sure the product code input shows the correct value
            if (lastProcessedBarcode != null) {
                binding.productCodeInput.setText(lastProcessedBarcode)
            }
        }
    }

    /**
     * Handle a scanned barcode, sending it to the appropriate input field based on focus
     * This method should NOT play sounds or clear barcode data - that's done by the caller
     * @return true if the barcode was handled for a specific input field, false if it should continue processing
     */
    private fun handleScannedBarcode(barcode: String): Boolean {
        // Enhanced logging to help diagnose focus issues
        val barcodeHasFocus = binding.barcodeInput.hasFocus()
        val productCodeHasFocus = binding.productCodeInput.hasFocus()
        val barcodeInputVisible = binding.barcodeInput.isVisible
        val productDetailsVisible = binding.productDetailsCard.isVisible
        
        Log.d(tag, "Processing barcode: $barcode")
        Log.d(tag, "Focus state - Barcode input: $barcodeHasFocus, Product code: $productCodeHasFocus")
        Log.d(tag, "Visibility state - Barcode section: $barcodeInputVisible, Product details: $productDetailsVisible")
        
        // If the barcode input has explicit focus, use it there
        if (barcodeHasFocus) {
            Log.d(tag, "Barcode input has focus - filling with scanned value")
            
            // If it's a valid product barcode format (starts with 69, 13 digits),
            // fill it directly in the barcode field
            if (barcode.startsWith("69") && barcode.length == 13 && barcode.all { it.isDigit() }) {
                binding.barcodeInput.setText(barcode)
                validateBarcode(barcode)
                Toast.makeText(requireContext(), getString(R.string.barcode_filled_input), Toast.LENGTH_SHORT).show()
                
                // If the product is displayed and barcode is valid, automatically save it
                if (lastDisplayedProduct != null && binding.productDetailsCard.isVisible && binding.saveBarcodeButton.isEnabled) {
                    Log.d(tag, "Automatically saving valid barcode")
                    
                    // Add a small delay to give visual feedback that the barcode was scanned before saving
                    view?.postDelayed({
                        saveProductBarcode()
                    }, 500) // 500ms delay
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.invalid_barcode_format), Toast.LENGTH_SHORT).show()
            }
            return true
        }
        
        // If the product code input has explicit focus, use it there
        if (productCodeHasFocus) {
            Log.d(tag, "Product code input has focus - filling with scanned value")
            
            // Set the product code input value
            binding.productCodeInput.setText(barcode)
            Toast.makeText(requireContext(), getString(R.string.barcode_filled_search), Toast.LENGTH_SHORT).show()
            
            // Automatically perform the search
            Log.d(tag, "Automatically performing search for: $barcode")
            fetchProductDetails(barcode)
            return true
        }
        
        // Second priority: If product details & barcode section are visible and the barcode field is empty,
        // check if we should fill the barcode field even without explicit focus
        if (productDetailsVisible && barcodeInputVisible && binding.barcodeInput.text.toString().isBlank() && barcode.startsWith("69") && barcode.length == 13) {
            Log.d(tag, "Product details visible and barcode field empty - filling with scanned value")
            binding.barcodeInput.setText(barcode)
            validateBarcode(barcode)
            binding.barcodeInput.requestFocus() // Explicitly request focus
            Toast.makeText(requireContext(), getString(R.string.barcode_filled_input), Toast.LENGTH_SHORT).show()
            
            // If the barcode is valid for saving, automatically save it after a short delay
            if (binding.saveBarcodeButton.isEnabled) {
                Log.d(tag, "Automatically saving valid barcode after delay")
                view?.postDelayed({
                    saveProductBarcode()
                }, 800) // 800ms delay to give time for visual feedback
            }
            
            return true
        }
        
        // If no input field has focus, perform a product search
        Log.d(tag, "No specific input has focus - using barcode for product search")
        
        // Store the barcode as last processed to prevent duplicates
        lastProcessedBarcode = barcode
        // Set the input field but don't trigger callbacks
        binding.productCodeInput.setText(barcode)
        // Actually search for the product
        fetchProductDetails(barcode)
        return false
    }

    override fun onStart() {
        super.onStart()
        // Ensure we start with a clean barcode state
        BarcodeEvent.clearLastBarcode()
        isProcessingBarcode = false
        Log.d(tag, "ProductInfoFragment onStart - cleared barcode state")
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        _binding = null
    }
    
    private fun fetchProductDetails(input: String) {
        // Cancel any existing job before starting a new one
        fetchJob?.cancel()
        
        binding.progressBar.isVisible = true
        binding.productDetailsCard.isVisible = false
        
        // Determine if input is a barcode or product code
        val isBarcode = input.startsWith("69") && input.length == 13 && input.all { it.isDigit() }
        
        // Save whether this search was performed using a barcode
        searchedByBarcode = isBarcode
        
        // Log the server address being used
        val serverAddress = PreferencesManager.getInstance(requireContext()).getServerAddress()
        Log.d(tag, "Fetching product details for ${if (isBarcode) "barcode" else "code"}: $input from server: $serverAddress")

        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Call appropriate API endpoint based on input type
                val response = if (isBarcode) {
                    // Search by barcode
                    NetworkModule.stockSyncApi.getProductByBarcode(input)
                } else {
                    // Search by product code
                    NetworkModule.stockSyncApi.getProductByCode(input)
                }
                
                binding.progressBar.isVisible = false
                
                Log.d(tag, "Response received: $response")
                
                if (response.isSuccessful && response.body() != null) {
                    val productResponse = response.body()!!
                    Log.d(tag, "Product response: $productResponse")
                    
                    if (productResponse.success) {
                        val product = productResponse.product
                        Log.d(tag, "Product found: ${product.code}, name: ${product.name}")
                        lastDisplayedProduct = product
                        displayProductDetails(product)
                    } else {
                        lastDisplayedProduct = null
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.product_not_found),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    lastDisplayedProduct = null
                    val errorMsg = when (response.code()) {
                        404 -> getString(R.string.error_product_not_found, input)
                        401 -> getString(R.string.error_authentication)
                        500 -> getString(R.string.error_server)
                        else -> getString(R.string.error_generic, response.code(), response.message() ?: "Unknown error")
                    }
                    Log.e(tag, "API error: $errorMsg")
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: ConnectException) {
                lastDisplayedProduct = null
                val errorMsg = getString(R.string.error_connection, serverAddress)
                handleError(errorMsg, e)
            } catch (e: SocketTimeoutException) {
                lastDisplayedProduct = null
                handleError(getString(R.string.error_timeout, serverAddress), e)
            } catch (e: UnknownHostException) {
                lastDisplayedProduct = null
                handleError(getString(R.string.error_unknown_host, serverAddress), e)
            } catch (e: IOException) {
                lastDisplayedProduct = null
                handleError(getString(R.string.error_network, e.message), e)
            } catch (e: HttpException) {
                lastDisplayedProduct = null
                handleError(getString(R.string.error_http, e.code(), e.message()), e)
            } catch (e: Exception) {
                lastDisplayedProduct = null
                handleError(getString(R.string.error_unexpected, e.message), e)
            }
        }
    }

    private fun displayProductDetails(product: Product) {
        binding.productDetailsCard.isVisible = true
        binding.productCodeValue.text = product.code
        binding.productNameValue.text = product.name
        binding.productDescValue.text = product.description
        binding.productQuantityValue.text = product.quantity.toString()
        
        // Display barcode if available
        if (product.barcode.isNullOrEmpty()) {
            binding.productBarcodeLabel.isVisible = false
            binding.productBarcodeValue.isVisible = false
        } else {
            binding.productBarcodeLabel.isVisible = true
            binding.productBarcodeValue.isVisible = true
            
            // Format the barcode to remove any decimal part if present
            val formattedBarcode = product.barcode.let {
                if (it.contains(".")) {
                    // If it's a decimal number, convert to Double first and then format to remove decimal part
                    it.toDoubleOrNull()?.toLong()?.toString() ?: it
                } else {
                    it
                }
            }
            
            binding.productBarcodeValue.text = formattedBarcode
            
            // Only pre-fill the barcode input field if NOT searched by barcode
            if (!searchedByBarcode) {
                binding.barcodeInput.setText(formattedBarcode)
            } else {
                // Clear the input field if searched by barcode
                binding.barcodeInput.setText("")
            }
        }
        
        // Set location text and apply styling based on whether location exists
        if (product.location.isNullOrEmpty()) {
            // Product has no location - apply red styling
            binding.productLocationValue.setText(R.string.no_location_indicator)
            binding.productLocationValue.setTextColor(android.graphics.Color.RED)
            binding.locationCard.setCardBackgroundColor("#FFEBEE".toColorInt()) // Light red
        } else {
            // Product has a location - use normal styling
            binding.productLocationValue.text = product.location
            binding.productLocationValue.setTextColor("#006064".toColorInt()) // Original teal color
            binding.locationCard.setCardBackgroundColor("#F5F5F5".toColorInt()) // Original gray
        }
        
        binding.productCostValue.text = getString(R.string.currency_format, product.cost)
        
        // Enable barcode save button if valid barcode is entered
        validateBarcode(binding.barcodeInput.text.toString())
    }

    private fun handleError(errorMessage: String, exception: Exception? = null) {
        binding.progressBar.isVisible = false
        Log.e(tag, "Error: $errorMessage", exception)
        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
    }
    
    override fun onStop() {
        super.onStop()
        // Clear last barcode when leaving the fragment to prevent processing in other fragments
        BarcodeEvent.clearLastBarcode()
        isProcessingBarcode = false
        Log.d(tag, "ProductInfoFragment onStop - cleared barcode state")
    }

    // Validate barcode format and enable/disable save button
    private fun validateBarcode(barcode: String) {
        val isValid = barcode.startsWith("69") && barcode.length == 13 && barcode.all { it.isDigit() }
        binding.saveBarcodeButton.isEnabled = isValid && lastDisplayedProduct != null
    }

    // Save product barcode to the server
    private fun saveProductBarcode() {
        val barcode = binding.barcodeInput.text.toString().trim()
        if (!validateBarcodeFormat(barcode)) {
            Toast.makeText(requireContext(), getString(R.string.invalid_barcode_format), Toast.LENGTH_SHORT).show()
            return
        }
        
        val productCode = lastDisplayedProduct?.code
        if (productCode.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No product selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.isVisible = true
        binding.saveBarcodeButton.isEnabled = false
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = BarcodeRequest(
                    code = productCode,
                    barcode = barcode
                )
                
                val response = NetworkModule.stockSyncApi.addBarcode(request)
                binding.progressBar.isVisible = false
                
                if (response.isSuccessful && response.body() != null) {
                    val barcodeResponse = response.body()!!
                    
                    if (barcodeResponse.success) {
                        Toast.makeText(
                            requireContext(),
                            barcodeResponse.message,
                            Toast.LENGTH_SHORT
                        ).show()
                        SoundUtil.playScanBeep()
                        
                        // Format the barcode to ensure no decimal part
                        val formattedBarcode = barcode.let {
                            if (it.contains(".")) {
                                it.toDoubleOrNull()?.toLong()?.toString() ?: it
                            } else {
                                it
                            }
                        }
                        
                        // Update the product's barcode in the UI
                        binding.productBarcodeLabel.isVisible = true
                        binding.productBarcodeValue.isVisible = true
                        binding.productBarcodeValue.text = formattedBarcode
                        
                        // Also update the lastDisplayedProduct object to include the barcode
                        lastDisplayedProduct = lastDisplayedProduct?.copy(barcode = formattedBarcode)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Failed to save barcode: ${barcodeResponse.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to save barcode: ${response.message() ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                handleError("Error saving barcode: ${e.message}", e)
            } finally {
                binding.saveBarcodeButton.isEnabled = true
            }
        }
    }
    
    private fun validateBarcodeFormat(barcode: String): Boolean {
        return barcode.startsWith("69") && barcode.length == 13 && barcode.all { it.isDigit() }
    }
} 