package com.example.myapplication.ui.movestock

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
import com.example.myapplication.data.MoveStockRequest
import com.example.myapplication.data.Product
import com.example.myapplication.databinding.FragmentMoveStockBinding
import com.example.myapplication.ui.scanner.CustomBarcodeScannerActivity
import com.example.myapplication.util.BarcodeEvent
import com.example.myapplication.util.PreferencesManager
import com.example.myapplication.util.SoundUtil
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import androidx.core.graphics.toColorInt
import com.example.myapplication.R

class MoveStockFragment : Fragment() {
    private val TAG = "MoveStockFragment"
    private var _binding: FragmentMoveStockBinding? = null
    private val binding get() = _binding!!
    
    // Track which field we're scanning for
    private enum class ScanTarget { PRODUCT_CODE, LOCATION }
    private var currentScanTarget = ScanTarget.PRODUCT_CODE
    private var isProcessingBarcode = false
    
    // Track if the current product exists in stock
    private var isProductInStock = false
    private var currentProductLocation: String? = null
    
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
                
                // Check if the product exists in stock
                checkIfProductExists(barcodeValue)
            }
            ScanTarget.LOCATION -> {
                binding.locationInput.setText(barcodeValue)
                // Focus on submit button after scanning location
                binding.changeLocationButton.requestFocus()
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
                        // Check if the product has stock (quantity > 0)
                        if ((product.quantity ?: 0)                                                                                         > 0) {
                            handleExistingProduct(product)
                        } else {
                            handleNoStockProduct(productCode)
                        }
                    } else {
                        handleNonExistentProduct(productCode)
                    }
                } else {
                    handleNonExistentProduct(productCode)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Log.e(TAG, "Error checking product: ${e.message}", e)
                handleError("Error checking product: ${e.message}")
            } finally {
                resetProcessingFlag()
            }
        }
    }
    
    private fun handleExistingProduct(product: Product) {
        isProductInStock = true
        currentProductLocation = product.location
        
        // Show current location in large text with appropriate styling
        if (product.location.isNullOrEmpty()) {
            // Product exists but has no location (未入库 = "Not in stock" in Chinese)
            binding.currentLocationValue.setText(R.string.no_location_indicator)
            binding.currentLocationValue.setTextColor(android.graphics.Color.RED)
            binding.currentLocationCard.setCardBackgroundColor("#FFEBEE".toColorInt()) // Light red
        } else {
            binding.currentLocationValue.text = product.location
            binding.currentLocationValue.setTextColor("#1976D2".toColorInt()) // Blue
            binding.currentLocationCard.setCardBackgroundColor("#E3F2FD".toColorInt()) // Light blue
        }
        
        binding.currentLocationCard.visibility = View.VISIBLE
        
        // Focus on location field for the next scan
        binding.locationInput.requestFocus()
        
        // Switch scan target to location
        currentScanTarget = ScanTarget.LOCATION
        
        // Show appropriate toast message
        if (product.location.isNullOrEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.move_product_no_location),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.move_product_with_location, product.location),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun handleNoStockProduct(productCode: String) {
        isProductInStock = false
        currentProductLocation = null
        
        // Hide current location card
        binding.currentLocationCard.visibility = View.GONE
        
        Toast.makeText(
            requireContext(),
            getString(R.string.product_no_stock, productCode),
            Toast.LENGTH_LONG
        ).show()
        
        // Clear the product code since operation cannot proceed
        binding.productCodeInput.text?.clear()
        binding.productCodeInput.requestFocus()
        currentScanTarget = ScanTarget.PRODUCT_CODE
    }
    
    private fun handleNonExistentProduct(productCode: String) {
        isProductInStock = false
        currentProductLocation = null
        
        // Hide current location card
        binding.currentLocationCard.visibility = View.GONE
        
        Toast.makeText(
            requireContext(),
            getString(R.string.product_not_exist, productCode),
            Toast.LENGTH_LONG
        ).show()
        
        // Clear the product code since operation cannot proceed
        binding.productCodeInput.text?.clear()
        binding.productCodeInput.requestFocus()
        currentScanTarget = ScanTarget.PRODUCT_CODE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoveStockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up the scan buttons
        binding.scanProductButton.setOnClickListener {
            currentScanTarget = ScanTarget.PRODUCT_CODE
            launchBarcodeScanner()
        }
        
        binding.scanLocationButton.setOnClickListener {
            currentScanTarget = ScanTarget.LOCATION
            launchBarcodeScanner()
        }
        
        // Set up the change location button
        binding.changeLocationButton.setOnClickListener {
            changeProductLocation()
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
    }
    
    override fun onStart() {
        super.onStart()
        // Ensure we start with a clean barcode state
        BarcodeEvent.clearLastBarcode()
        isProcessingBarcode = false
        
        // Restore product location display if we had a product in stock
        if (isProductInStock && binding.productCodeInput.text?.isNotEmpty() == true) {
            binding.currentLocationCard.visibility = View.VISIBLE
            
            // If we have a location input, focus there, otherwise focus on product code
            if (binding.locationInput.text?.isNotEmpty() == true) {
                binding.locationInput.requestFocus()
                currentScanTarget = ScanTarget.LOCATION
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

    private fun changeProductLocation() {
        val productCode = binding.productCodeInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()

        // Validate inputs
        if (productCode.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_enter_product_code), Toast.LENGTH_SHORT).show()
            return
        }

        if (location.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_enter_location), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Verify product is in stock before proceeding
        if (!isProductInStock) {
            Toast.makeText(requireContext(), getString(R.string.cannot_move_product_not_in_stock), Toast.LENGTH_LONG).show()
            // Check the product status first if not already verified
            if (currentProductLocation == null) {
                checkIfProductExists(productCode)
            }
            return
        }

        moveProductLocation(productCode, location)
    }

    private fun moveProductLocation(productCode: String, location: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        // Log the server address being used
        val serverAddress = PreferencesManager.getInstance(requireContext()).getServerAddress()
        Log.d(TAG, "Changing location for product: $productCode to: $location - Server: $serverAddress")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = MoveStockRequest(
                    code = productCode,
                    location = location
                )

                val response = NetworkModule.stockSyncApi.changeProductLocation(request)
                binding.progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.location_changed_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Reset all state after successful operation
                    resetAfterSuccessfulOperation()
                } else {
                    val errorMsg = when (response.code()) {
                        404 -> "Product not found (404): The product with code '$productCode' doesn't exist"
                        401 -> "Authentication error (401): Not authorized to access this resource"
                        500 -> "Server error (500): The API server encountered an error"
                        else -> "Error: ${response.code()} - ${response.message() ?: "Unknown error"}"
                    }
                    Log.e(TAG, "API error: $errorMsg")
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: ConnectException) {
                handleError(getString(R.string.connection_error), e)
            } catch (e: SocketTimeoutException) {
                handleError(getString(R.string.connection_timeout), e)
            } catch (e: UnknownHostException) {
                handleError(getString(R.string.unknown_host), e)
            } catch (e: IOException) {
                handleError("Network error: ${e.message}", e)
            } catch (e: HttpException) {
                handleError("HTTP error ${e.code()}: ${e.message()}", e)
            } catch (e: Exception) {
                handleError("Unexpected error: ${e.message}", e)
            }
        }
    }
    
    private fun resetAfterSuccessfulOperation() {
        // Reset state variables
        isProductInStock = false
        currentProductLocation = null
        
        // Reset UI
        binding.currentLocationCard.visibility = View.GONE
        
        // Clear inputs
        clearInputs()
        
        // Set focus to product code for the next scan
        binding.productCodeInput.requestFocus()
        
        // Reset scan target to product
        currentScanTarget = ScanTarget.PRODUCT_CODE
    }

    private fun clearInputs() {
        binding.productCodeInput.text?.clear()
        binding.locationInput.text?.clear()
    }

    private fun handleError(errorMessage: String, exception: Exception? = null) {
        binding.progressBar.visibility = View.GONE
        Log.e(TAG, "Error: $errorMessage", exception)
        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        // Clear last barcode when leaving the fragment to prevent processing in other fragments
        BarcodeEvent.clearLastBarcode()
    }
} 