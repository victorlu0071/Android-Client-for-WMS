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
        
        when (currentScanTarget) {
            ScanTarget.PRODUCT_CODE -> {
                binding.productCodeInput.setText(barcodeValue)
                
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
                // Focus on submit button after scanning location
                binding.submitButton.requestFocus()
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
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = NetworkModule.stockSyncApi.getProductByCode(productCode)
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
        
        // Set up the scan buttons
        binding.scanProductButton.setOnClickListener {
            currentScanTarget = ScanTarget.PRODUCT_CODE
            launchBarcodeScanner()
        }
        
        binding.scanLocationButton.setOnClickListener {
            currentScanTarget = ScanTarget.LOCATION
            launchBarcodeScanner()
        }
        
        // Set up the submit button
        binding.submitButton.setOnClickListener {
            submitStockChange()
        }
        
        // Observe barcode events using LiveData
        BarcodeEvent.barcodeData.observe(viewLifecycleOwner) { barcode ->
            // Only process non-empty barcodes and ones that aren't from a tab switch
            if (!barcode.isNullOrEmpty() && barcode.trim().isNotEmpty() && !isProcessingBarcode) {
                // Play a beep sound when barcode is received
                SoundUtil.playScanBeep()
                processBarcodeResult(barcode)
            }
        }
        
        // Initialize with "Add Stock" selected by default
        binding.inStockRadio.isChecked = true
        handleInStockSelected()
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
        val quantityStr = binding.quantityInput.text.toString().trim()
        
        if (productCode.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a product code", Toast.LENGTH_SHORT).show()
            return
        }

        val quantity = try {
            if (quantityStr.isEmpty()) 1 else quantityStr.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(requireContext(), "Invalid quantity", Toast.LENGTH_SHORT).show()
            return
        }

        if (binding.inStockRadio.isChecked) {
            // For adding stock, use the current location from existing product or the input field
            val location = if (isProductExisting && currentProductLocation != null) {
                currentProductLocation!!
            } else {
                binding.locationInput.text.toString().trim()
            }
            
            // Verify location is provided for new products
            if (!isProductExisting && location.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a location for the new product", Toast.LENGTH_SHORT).show()
                return
            }
            
            addStock(productCode, quantity, location)
        } else {
            removeStock(productCode, quantity)
        }
    }

    private fun addStock(productCode: String, quantity: Int, location: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = InStockRequest(
                    code = productCode,
                    quantity = quantity,
                    location = location.ifEmpty { null }
                )
                
                val response = NetworkModule.stockSyncApi.addStock(request)
                binding.progressBar.visibility = View.GONE
                
                if (response.isSuccessful) {
                    Toast.makeText(
                        requireContext(),
                        "Stock added successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // After successful operation, reset flags for the next scan
                    resetAfterSuccessfulOperation()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${response.message()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: IOException) {
                handleError("Network error: ${e.message}")
            } catch (e: HttpException) {
                handleError("HTTP error ${e.code()}: ${e.message()}")
            } catch (e: Exception) {
                handleError("Unexpected error: ${e.message}")
            }
        }
    }

    private fun removeStock(productCode: String, quantity: Int) {
        binding.progressBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = OutStockRequest(
                    code = productCode,
                    quantity = quantity
                )
                
                val response = NetworkModule.stockSyncApi.removeStock(request)
                binding.progressBar.visibility = View.GONE
                
                if (response.isSuccessful) {
                    Toast.makeText(
                        requireContext(),
                        "Stock removed successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // After successful operation, reset flags for the next scan
                    resetAfterSuccessfulOperation()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${response.message()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: IOException) {
                handleError("Network error: ${e.message}")
            } catch (e: HttpException) {
                handleError("HTTP error ${e.code()}: ${e.message()}")
            } catch (e: Exception) {
                handleError("Unexpected error: ${e.message}")
            }
        }
    }
    
    private fun resetAfterSuccessfulOperation() {
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
    
    private fun handleError(errorMessage: String) {
        binding.progressBar.visibility = View.GONE
        Log.e(tAG, "Error: $errorMessage")
        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        // Clear last barcode when leaving the fragment to prevent processing in other fragments
        BarcodeEvent.clearLastBarcode()
    }
} 