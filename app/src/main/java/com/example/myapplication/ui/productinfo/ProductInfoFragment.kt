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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.api.NetworkModule
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
import androidx.core.graphics.toColorInt

class ProductInfoFragment : Fragment() {
    private val TAG = "ProductInfoFragment"
    private var _binding: FragmentProductInfoBinding? = null
    private val binding get() = _binding!!
    
    // Track the last processed barcode to prevent duplicate processing
    private var lastProcessedBarcode: String? = null
    private var fetchJob: Job? = null
    private var isProcessingBarcode = false
    
    // Store the last displayed product to persist between tab changes
    private var lastDisplayedProduct: Product? = null
    
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
        // When processing a barcode right after returning to this fragment
        // we want to process it even if it's the same as before
        val isSameAsPrevious = barcodeValue == lastProcessedBarcode
        
        // Check if we've already processed this barcode recently in the same session
        if (isSameAsPrevious && lastDisplayedProduct != null && binding.productDetailsCard.visibility == View.VISIBLE) {
            Log.d(TAG, "Ignoring duplicate barcode in the same session: $barcodeValue")
            return
        }
        
        isProcessingBarcode = true
        lastProcessedBarcode = barcodeValue
        
        // Set the scanned barcode value to the input field
        binding.productCodeInput.setText(barcodeValue)
        
        // Cancel any existing fetch job
        fetchJob?.cancel()
        
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

        // Search button click listener
        binding.searchButton.setOnClickListener {
            val productCode = binding.productCodeInput.text.toString().trim()
            if (productCode.isNotEmpty()) {
                // Reset processing flags when manually searching
                isProcessingBarcode = false
                lastProcessedBarcode = null
                fetchProductDetails(productCode)
            } else {
                Toast.makeText(requireContext(), getString(R.string.please_enter_product_code_search), Toast.LENGTH_SHORT).show()
            }
        }
        
        // Scan button click listener
        binding.scanButton.setOnClickListener {
            launchBarcodeScanner()
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
        
        // Restore the last product if available
        lastDisplayedProduct?.let { product ->
            displayProductDetails(product)
            // Make sure the product code input shows the correct value
            if (lastProcessedBarcode != null) {
                binding.productCodeInput.setText(lastProcessedBarcode)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Ensure we start with a clean barcode state
        BarcodeEvent.clearLastBarcode()
        isProcessingBarcode = false
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        _binding = null
    }
    
    private fun launchBarcodeScanner() {
        val intent = Intent(requireContext(), CustomBarcodeScannerActivity::class.java)
        scanBarcodeLauncher.launch(intent)
    }

    private fun fetchProductDetails(productCode: String) {
        // Cancel any existing job before starting a new one
        fetchJob?.cancel()
        
        binding.progressBar.visibility = View.VISIBLE
        binding.productDetailsCard.visibility = View.GONE
        
        // Log the server address being used
        val serverAddress = PreferencesManager.getInstance(requireContext()).getServerAddress()
        Log.d(TAG, "Fetching product details for code: $productCode from server: $serverAddress")

        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = NetworkModule.stockSyncApi.getProductByCode(productCode)
                binding.progressBar.visibility = View.GONE
                
                Log.d(TAG, "Response received: $response")
                
                if (response.isSuccessful && response.body() != null) {
                    val productResponse = response.body()!!
                    Log.d(TAG, "Product response: $productResponse")
                    
                    if (productResponse.success) {
                        val product = productResponse.product
                        Log.d(TAG, "Product found: ${product.code}, name: ${product.name}")
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
                        404 -> getString(R.string.error_product_not_found, productCode)
                        401 -> getString(R.string.error_authentication)
                        500 -> getString(R.string.error_server)
                        else -> getString(R.string.error_generic, response.code(), response.message() ?: "Unknown error")
                    }
                    Log.e(TAG, "API error: $errorMsg")
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
        binding.productDetailsCard.visibility = View.VISIBLE
        binding.productCodeValue.text = product.code
        binding.productNameValue.text = product.name
        binding.productDescValue.text = product.description
        binding.productQuantityValue.text = product.quantity.toString()
        
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