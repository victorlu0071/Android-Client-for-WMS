package com.example.myapplication.ui.stock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.api.NetworkModule
import com.example.myapplication.data.InStockRequest
import com.example.myapplication.data.OutStockRequest
import com.example.myapplication.data.Product
import com.example.myapplication.databinding.FragmentStockBinding
import com.example.myapplication.ui.scanner.CustomBarcodeScannerActivity
import com.example.myapplication.util.BarcodeEvent
import com.example.myapplication.util.SoundUtil
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import androidx.core.graphics.toColorInt
import com.example.myapplication.R
import androidx.core.content.ContextCompat


class StockFragment : Fragment() {
    private val tAG = "StockFragment"
    private var _binding: FragmentStockBinding? = null
    private val binding get() = _binding!!
    
    // Track which field we're scanning for
    private enum class ScanTarget { PRODUCT_CODE, LOCATION }
    private var currentScanTarget = ScanTarget.PRODUCT_CODE
    private var isProcessingBarcode = false
    
    // Track the last scanned product for quantity incrementing
    private var lastScannedProductCode: String? = null
    private var currentProductLocation: String? = null
    private var isProductExisting = false
    
    // Register for the barcode scanner activity result
    private val scanBarcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val barcodeValue = data?.getStringExtra(CustomBarcodeScannerActivity.BARCODE_RESULT)
            
            if (!barcodeValue.isNullOrEmpty() && !isProcessingBarcode) {
                processBarcodeResult(barcodeValue)
            }
        }
    }
    
    private fun processBarcodeResult(barcodeValue: String) {
        isProcessingBarcode = true
        Log.d(tAG, "Processing barcode: $barcodeValue, current target: $currentScanTarget")
        
        when (currentScanTarget) {
            ScanTarget.PRODUCT_CODE -> {
                binding.productCodeInput.setText(barcodeValue)
                Toast.makeText(requireContext(), getString(R.string.barcode_filled_search), Toast.LENGTH_SHORT).show()
                
                // If scanning the same product code again, increment quantity
                if (barcodeValue == lastScannedProductCode && isProductExisting) {
                    incrementQuantity()
                    resetProcessingFlag()
                    return
                }
                
                // Check if the product already exists in the system
                checkIfProductExists(barcodeValue)
            }
            ScanTarget.LOCATION -> {
                binding.locationInput.setText(barcodeValue)
                Toast.makeText(requireContext(), getString(R.string.location_set), Toast.LENGTH_SHORT).show()
                
                // Focus on submit button after scanning location
                binding.submitButton.requestFocus()
                
                // If product code is filled, location is filled, and quantity is valid,
                // automatically submit the form after a short delay
                val productCode = binding.productCodeInput.text.toString().trim()
                val qtyStr = binding.quantityInput.text.toString().trim()
                
                try {
                    val qty = if (qtyStr.isEmpty()) 1 else qtyStr.toInt()
                    
                    if (productCode.isNotEmpty() && qty > 0) {
                        Log.d(tAG, "All fields filled, automatically submitting after delay")
                        
                        // Auto-submit after a short delay to allow the user to see the scanned location
                        Toast.makeText(requireContext(), getString(R.string.auto_submitting), Toast.LENGTH_SHORT).show()
                        
                        view?.postDelayed({
                            submitStockChange()
                        }, 1000) // 1 second delay
                    }
                } catch (e: NumberFormatException) {
                    // Invalid quantity, don't auto-submit
                }
                
                resetProcessingFlag()
            }
        }
    }
    
    private fun resetProcessingFlag() {
        // Reset processing flag after a delay
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            isProcessingBarcode = false
        }
    }
    
    private fun incrementQuantity() {
        try {
            val currentQty = binding.quantityInput.text.toString().toInt()
            binding.quantityInput.setText((currentQty + 1).toString())
            Toast.makeText(
                requireContext(),
                getString(R.string.quantity_increased, currentQty + 1),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: NumberFormatException) {
            binding.quantityInput.setText(getString(R.string._1))
        }
    }
    
    private fun checkIfProductExists(productCode: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        // Determine if the input is a barcode (starts with 69 and is 13 digits)
        val isBarcode = productCode.startsWith("69") && productCode.length == 13
        Log.d(tAG, "Checking product with ${if (isBarcode) "barcode" else "code"}: $productCode")
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Call the appropriate API endpoint based on input format
                val response = if (isBarcode) {
                    NetworkModule.stockSyncApi.getProductByBarcode(productCode)
                } else {
                    NetworkModule.stockSyncApi.getProductByCode(productCode)
                }
                
                binding.progressBar.visibility = View.GONE
                
                if (response.isSuccessful && response.body() != null) {
                    val productResponse = response.body()!!
                    
                    if (productResponse.success) {
                        val product = productResponse.product
                        handleExistingProduct(product, productCode)
                    } else {
                        // Product doesn't exist yet, need a location for it
                        handleNewProduct(productCode)
                    }
                } else {
                    // Product doesn't exist yet, need a location for it
                    handleNewProduct(productCode)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Log.e(tAG, "Error checking product: ${e.message}", e)
                // Assume product doesn't exist if there's an error
                handleNewProduct(productCode)
            } finally {
                resetProcessingFlag()
            }
        }
    }
    
    private fun handleExistingProduct(product: Product, productCode: String) {
        isProductExisting = true
        lastScannedProductCode = productCode
        currentProductLocation = product.location
        
        // Configure the existing location card based on whether the product has a location
        if (product.location.isNullOrEmpty()) {
            // Product exists but has no location (未入库 = "Not in stock" in Chinese)
            binding.existingLocationValue.setText(R.string.no_location_indicator)
            binding.existingLocationValue.setTextColor(android.graphics.Color.RED)
            binding.existingLocationCard.setCardBackgroundColor("#FFEBEE".toColorInt()) // Light red
            binding.existingLocationHint.setText(R.string.product_no_location_hint)
        } else {
            // Product exists with location
            binding.existingLocationValue.text = product.location
            binding.existingLocationValue.setTextColor("#2E7D32".toColorInt()) // Dark green
            binding.existingLocationCard.setCardBackgroundColor("#E8F5E9".toColorInt()) // Light green
            binding.existingLocationHint.setText(R.string.product_already_in_stock_at_this_location)
        }
        
        binding.existingLocationCard.visibility = View.VISIBLE
        
        // If in "Add Stock" mode, show location input for items without location
        if (binding.inStockRadio.isChecked) {
            binding.locationContainer.visibility = if (product.location.isNullOrEmpty()) View.VISIBLE else View.GONE
        }
        
        // Focus on quantity for quick edit
        binding.quantityInput.requestFocus()
        
        // Select all text in quantity field for easy overwriting
        binding.quantityInput.selectAll()
        
        // Show appropriate toast message
        if (product.location.isNullOrEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.product_found_no_location),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.product_found_with_location, product.location),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun handleNewProduct(productCode: String) {
        isProductExisting = false
        lastScannedProductCode = productCode
        currentProductLocation = null
        
        // Hide existing location card
        binding.existingLocationCard.visibility = View.GONE
        
        // Show location input if in "Add Stock" mode
        if (binding.inStockRadio.isChecked) {
            binding.locationContainer.visibility = View.VISIBLE
            
            // Set focus on location field or the scan location button
            binding.locationInput.requestFocus()
            
            // Switch scan target to location
            currentScanTarget = ScanTarget.LOCATION
            
            Toast.makeText(
                requireContext(),
                getString(R.string.new_product_scan_location),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // For removing stock, we don't need location
            binding.quantityInput.requestFocus()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set up the radio buttons
        binding.inStockRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                handleInStockSelected()
            }
        }
        
        binding.outStockRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                handleOutStockSelected()
            }
        }
        
        // Enhanced input field behaviors
        setupProductCodeField()
        setupLocationField()
        setupQuantityField()
        
        // Set up the submit button
        binding.submitButton.setOnClickListener {
            submitStockChange()
        }
        
        // Observe barcode events using LiveData
        BarcodeEvent.barcodeData.observe(viewLifecycleOwner) { barcode ->
            // Only process non-empty barcodes and ones that aren't from a tab switch
            if (!barcode.isNullOrEmpty() && barcode.trim().isNotEmpty() && !isProcessingBarcode) {
                Log.d(tAG, "BarcodeEvent received: $barcode")
                
                // Only play a sound if one hasn't been played already for this barcode
                if (!BarcodeEvent.hasSoundBeenPlayed()) {
                    Log.d(tAG, "Playing sound for barcode: $barcode")
                    SoundUtil.playScanBeep()
                    BarcodeEvent.markSoundPlayed()
                }
                
                processBarcodeResult(barcode)
                
                // Clear the barcode from the event after processing
                BarcodeEvent.clearLastBarcode()
            }
        }
        
        // Initialize with "Add Stock" selected by default
        binding.inStockRadio.isChecked = true
        handleInStockSelected()
    }
    
    /**
     * Setup enhanced behavior for the product code field
     */
    private fun setupProductCodeField() {
        // Improve visibility when focused
        binding.productCodeInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.productCodeInput.setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9")) // Light green
                currentScanTarget = ScanTarget.PRODUCT_CODE
                Log.d(tAG, "Product code field has focus")
            } else {
                binding.productCodeInput.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        
        // Add text change listener to automatically search for product
        binding.productCodeInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Don't auto-search while we're processing a barcode to prevent duplicate searches
                if (!isProcessingBarcode && (s?.length ?: 0) >= 5) {
                    val code = s.toString().trim()
                    if (code.isNotEmpty()) {
                        // Small delay before auto-searching
                        view?.postDelayed({
                            if (code == binding.productCodeInput.text.toString().trim()) {
                                Log.d(tAG, "Auto-searching for product: $code")
                                checkIfProductExists(code)
                            }
                        }, 800) // 800ms delay
                    }
                }
            }
        })
        
        // Make the entire input layout clickable for easier focus
        binding.productCodeLayout.setOnClickListener {
            binding.productCodeInput.requestFocus()
        }
    }
    
    /**
     * Setup enhanced behavior for the location field
     */
    private fun setupLocationField() {
        // Improve visibility when focused
        binding.locationInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.locationInput.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_blue))
                currentScanTarget = ScanTarget.LOCATION
                Log.d(tAG, "Location field has focus")
            } else {
                binding.locationInput.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        
        // Add keyboard action listener for auto-submit
        binding.locationInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                
                // Hide keyboard
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.locationInput.windowToken, 0)
                
                // Check if we can auto-submit
                val productCode = binding.productCodeInput.text.toString().trim()
                val qtyStr = binding.quantityInput.text.toString().trim()
                
                try {
                    val qty = if (qtyStr.isEmpty()) 1 else qtyStr.toInt()
                    if (productCode.isNotEmpty() && qty > 0) {
                        Log.d(tAG, "Location entered, auto-submitting")
                        submitStockChange()
                        return@setOnEditorActionListener true
                    }
                } catch (e: NumberFormatException) {
                    // Invalid quantity, don't auto-submit
                }
            }
            false
        }
        
        // Make the entire input layout clickable for easier focus
        binding.locationLayout.setOnClickListener {
            binding.locationInput.requestFocus()
        }
    }
    
    /**
     * Setup enhanced behavior for the quantity field
     */
    private fun setupQuantityField() {
        // Allow incrementing/decrementing with arrow keys or +/- keys
        binding.quantityInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val currentQty = try {
                    binding.quantityInput.text.toString().toInt()
                } catch (e: NumberFormatException) {
                    1
                }
                
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_PLUS -> {
                        binding.quantityInput.setText((currentQty + 1).toString())
                        return@setOnKeyListener true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_MINUS -> {
                        if (currentQty > 1) {
                            binding.quantityInput.setText((currentQty - 1).toString())
                        }
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
    
    override fun onStart() {
        super.onStart()
        // Ensure we start with a clean barcode state
        BarcodeEvent.clearLastBarcode()
        isProcessingBarcode = false
        
        // If we have a previously scanned product, restore the UI state
        if (lastScannedProductCode != null && lastScannedProductCode!!.isNotEmpty()) {
            // If we return to the fragment and there's a product code, make sure
            // the product information is visible if applicable
            if (isProductExisting && currentProductLocation != null) {
                binding.existingLocationCard.visibility = View.VISIBLE
                
                // Update UI based on in/out stock selection
                if (binding.inStockRadio.isChecked) {
                    handleInStockSelected()
                } else {
                    handleOutStockSelected()
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private fun launchBarcodeScanner() {
        val intent = Intent(requireContext(), CustomBarcodeScannerActivity::class.java)
        scanBarcodeLauncher.launch(intent)
    }

    // Handle In Stock radio button selection
    private fun handleInStockSelected() {
        // Show location container for new products
        binding.locationContainer.visibility = if (isProductExisting) View.GONE else View.VISIBLE
        
        // Show existing location card if applicable
        if (isProductExisting && lastScannedProductCode != null) {
            binding.existingLocationCard.visibility = View.VISIBLE
        }
    }
    
    // Handle Out Stock radio button selection
    private fun handleOutStockSelected() {
        // Hide location container for out-stock operations
        binding.locationContainer.visibility = View.GONE
        
        // Hide existing location card for out-stock operations
        binding.existingLocationCard.visibility = View.GONE
    }
    
    // Submit stock change (renamed from handleSubmitAction)
    private fun submitStockChange() {
        val productCode = binding.productCodeInput.text.toString().trim()
        val qtyStr = binding.quantityInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()

        if (productCode.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_enter_product_code), Toast.LENGTH_SHORT).show()
            return
        }

        val qty = if (qtyStr.isNotEmpty()) {
            try {
                qtyStr.toInt()
            } catch (e: NumberFormatException) {
                Toast.makeText(requireContext(), "Please enter a valid quantity", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            1 // Default quantity
        }

        // For outstock, ensure product exists
        if (binding.outStockRadio.isChecked && !isProductExisting) {
            Toast.makeText(requireContext(), "Cannot remove stock for a product that doesn't exist", Toast.LENGTH_LONG).show()
            return
        }

        // For instock with new product, location is required
        if (binding.inStockRadio.isChecked && !isProductExisting && location.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_enter_location), Toast.LENGTH_SHORT).show()
            binding.locationInput.requestFocus()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE

        // Determine if the input is a barcode
        val isBarcode = productCode.startsWith("69") && productCode.length == 13
        
        // Execute the appropriate API call based on the selected radio button
        if (binding.inStockRadio.isChecked) {
            addStock(productCode, isBarcode, qty, location)
        } else {
            removeStock(productCode, isBarcode, qty)
        }
    }

    private fun addStock(productCode: String, isBarcode: Boolean, quantity: Int, location: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Create request based on whether we have a barcode or product code
                val request = if (isBarcode) {
                    InStockRequest(
                        barcode = productCode,
                        quantity = quantity,
                        location = location
                    )
                } else {
                    InStockRequest(
                        code = productCode,
                        quantity = quantity,
                        location = location
                    )
                }

                val response = NetworkModule.stockSyncApi.addStock(request)
                binding.progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        // Show success message
                        Toast.makeText(requireContext(), body.message, Toast.LENGTH_SHORT).show()
                        // Reset UI
                        resetForm()
                    } else {
                        Toast.makeText(requireContext(), "Unknown server response", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errorMsg = when (response.code()) {
                        404 -> "Product not found (404): The product with code '$productCode' doesn't exist"
                        401 -> "Authentication error (401): Not authorized to access this resource"
                        500 -> "Server error (500): The API server encountered an error"
                        else -> "Error: ${response.code()} - ${response.message() ?: "Unknown error"}"
                    }
                    Log.e(tAG, "API error: $errorMsg")
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                handleApiError(e)
            }
        }
    }

    private fun removeStock(productCode: String, isBarcode: Boolean, quantity: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Create request based on whether we have a barcode or product code
                val request = if (isBarcode) {
                    OutStockRequest(
                        barcode = productCode,
                        quantity = quantity
                    )
                } else {
                    OutStockRequest(
                        code = productCode,
                        quantity = quantity
                    )
                }

                val response = NetworkModule.stockSyncApi.removeStock(request)
                binding.progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        // Show success message
                        Toast.makeText(requireContext(), body.message, Toast.LENGTH_SHORT).show()
                        // Reset UI
                        resetForm()
                    } else {
                        Toast.makeText(requireContext(), "Unknown server response", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errorMsg = when (response.code()) {
                        404 -> "Product not found (404): The product with code '$productCode' doesn't exist"
                        401 -> "Authentication error (401): Not authorized to access this resource"
                        500 -> "Server error (500): The API server encountered an error"
                        else -> "Error: ${response.code()} - ${response.message() ?: "Unknown error"}"
                    }
                    Log.e(tAG, "API error: $errorMsg")
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                handleApiError(e)
            }
        }
    }
    
    private fun resetForm() {
        // Reset state variables for the next operation
        isProductExisting = false
        lastScannedProductCode = null
        currentProductLocation = null
        
        // Reset UI
        binding.existingLocationCard.visibility = View.GONE
        if (binding.inStockRadio.isChecked) {
            binding.locationContainer.visibility = View.VISIBLE
        }
        
        // Clear inputs
        clearInputs()
        
        // Set focus to product code for the next scan
        binding.productCodeInput.requestFocus()
        
        // Reset scan target to product
        currentScanTarget = ScanTarget.PRODUCT_CODE
    }

    private fun clearInputs() {
        binding.productCodeInput.text?.clear()
        binding.quantityInput.setText("1")
        binding.locationInput.text?.clear()
    }
    
    private fun handleApiError(e: Exception) {
        binding.progressBar.visibility = View.GONE
        Log.e(tAG, "API error: ${e.message}", e)
        Toast.makeText(requireContext(), "An error occurred", Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        // Clear last barcode when leaving the fragment to prevent processing in other fragments
        BarcodeEvent.clearLastBarcode()
    }
} 