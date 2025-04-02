package com.example.myapplication.ui.addproduct

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.api.NetworkModule
import com.example.myapplication.data.Product
import com.example.myapplication.databinding.ActivityAddProductBinding
import com.example.myapplication.ui.scanner.CustomBarcodeScannerActivity
import com.example.myapplication.ui.camera.MultiCaptureCameraActivity
import com.example.myapplication.util.BarcodeEvent
import com.example.myapplication.util.ImageUtil
import com.example.myapplication.util.PreferencesManager
import com.example.myapplication.util.SoundUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class AddProductActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AddProductActivity"
    }

    private lateinit var binding: ActivityAddProductBinding
    private lateinit var preferencesManager: PreferencesManager
    
    // For image capture
    private var imageFiles = mutableListOf<File>()
    private var currentPhotoPath: String = ""
    
    // For multi-capture mode
    private var isMultiCaptureMode = false
    private var multiCaptureCount = 0
    
    // Activity result launcher for image capture
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // A photo was taken successfully
            val photoFile = File(currentPhotoPath)
            Log.d(TAG, "Photo captured successfully at: ${photoFile.absolutePath}")
            if (photoFile.exists()) {
                Log.d(TAG, "File exists with size: ${photoFile.length()} bytes")
                imageFiles.add(photoFile)
                updateImageGallery()
                Toast.makeText(this, getString(R.string.photo_added_gallery), Toast.LENGTH_SHORT).show()
                
                // Update multi-capture mode if active
                if (isMultiCaptureMode) {
                    multiCaptureCount++
                    updateMultiCaptureStatus()
                }
            } else {
                Log.e(TAG, "Photo file doesn't exist at path: $currentPhotoPath")
                Toast.makeText(this, getString(R.string.error_photo_not_found), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Photo capture failed or was canceled
            Log.e(TAG, "Photo capture failed or was canceled by user (result code: ${result.resultCode})")
            Toast.makeText(this, getString(R.string.photo_capture_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    // Permission launcher for camera
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // All permissions granted
            dispatchTakePictureIntent()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    // Activity result launcher for multi-capture camera
    private val multiCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Process the returned images
            val imagePaths = result.data?.getStringArrayListExtra(MultiCaptureCameraActivity.EXTRA_IMAGE_PATHS)
            if (!imagePaths.isNullOrEmpty()) {
                Log.d(TAG, "Retrieved ${imagePaths.size} images from MultiCaptureCameraActivity")
                
                // Add each image to our collection
                for (path in imagePaths) {
                    val photoFile = File(path)
                    if (photoFile.exists()) {
                        Log.d(TAG, "Adding image: ${photoFile.absolutePath}, size: ${photoFile.length()} bytes")
                        imageFiles.add(photoFile)
                    } else {
                        Log.e(TAG, "Photo file doesn't exist at path: $path")
                    }
                }
                
                // Update the UI with the new images
                updateImageGallery()
                Toast.makeText(this, getString(R.string.photos_added_gallery, imagePaths.size), Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "No images returned from camera activity")
                Toast.makeText(this, getString(R.string.no_photos_captured), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Camera activity was canceled or failed
            Log.e(TAG, "MultiCaptureCameraActivity failed or was canceled (result code: ${result.resultCode})")
            Toast.makeText(this, getString(R.string.photo_capture_canceled), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProductBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_new_product)

        preferencesManager = PreferencesManager.getInstance(applicationContext)

        // Set initial UI state
        setupInitialState()
        
        // Enhanced input fields with visual feedback and autocomplete
        setupEnhancedInputFields()
        
        // Set up barcode receiver
        setupBarcodeReceiver()
        
        binding.buttonSave.setOnClickListener {
            saveProduct()
        }
        
        // Set up image capture button
        binding.buttonTakePhoto.setOnClickListener {
            launchMultiCaptureCamera()
        }
        
        // The multi-capture UI is no longer needed since we're using a custom camera
        // So let's hide these elements during initialization
        binding.multiCaptureContainer.visibility = View.GONE
        
        // Initialize API lookup switch from preferences
        binding.switchApiLookup.isChecked = preferencesManager.getUseApiLookup()
        
        // Set up listener for API lookup switch
        binding.switchApiLookup.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveUseApiLookup(isChecked)
        }
    }

    // Add support for physical scan button
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if this key matches the bound scan button
        val boundScanKey = preferencesManager.getScanButtonKeyCode()
        
        if (boundScanKey != null && keyCode == boundScanKey) {
            // Launch the barcode scanner with the appropriate scanning flag based on focus
            BarcodeEvent.clearLastBarcode()
            val intent = Intent(this, CustomBarcodeScannerActivity::class.java)
            
            // Only scan for barcode now that the product code field is removed
            intent.putExtra("SCAN_FOR_BARCODE", true)
            
            Log.d(TAG, "Launching barcode scanner, scanning for barcode")
            startActivity(intent)
            return true
        }
        
        return super.onKeyDown(keyCode, event)
    }

    private fun setupInitialState() {
        // Initialize image count display
        updateImageCountDisplay()
        
        // Set up image gallery container
        binding.imageGalleryContainer.removeAllViews()
    }

    /**
     * Updates the image count display text based on the number of images
     */
    private fun updateImageCountDisplay() {
        // Set the text showing how many images have been captured
        binding.textImageCount.text = getString(R.string.image_count_format, imageFiles.size)
    }

    /**
     * Setup enhanced input fields with visual feedback and auto-completion
     */
    private fun setupEnhancedInputFields() {
        // Product code field removed
        
        // Barcode field enhancements
        binding.editTextBarcode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.editTextBarcode.setBackgroundColor(ContextCompat.getColor(this, R.color.light_blue)) // Use KTX extension
                Log.d(TAG, "Barcode field has focus")
            } else {
                binding.editTextBarcode.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        
        // Make the barcode field and layout clickable for easier focus
        binding.editTextBarcode.setOnClickListener {
            binding.editTextBarcode.requestFocus()
        }
        
        // Auto-capitalization for name field
        binding.editTextName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.isNotEmpty() == true && s.length == 1) {
                    s.replace(0, 1, s.substring(0, 1).uppercase())
                }
            }
        })
        
        // Format price input for cost field
        binding.editTextCost.addTextChangedListener(object : android.text.TextWatcher {
            private var isFormatting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isFormatting) return
                isFormatting = true
                
                val text = s.toString().replace("[^\\d.]".toRegex(), "")
                try {
                    val price = text.toDoubleOrNull() ?: 0.0
                    // Format to 2 decimal places without the currency symbol
                    val formatted = String.format(Locale.US, "%.2f", price)
                    if (formatted != s.toString()) {
                        s?.replace(0, s.length, formatted)
                    }
                } catch (_: Exception) {
                    // Just leave the text as is if there's an error
                }
                
                isFormatting = false
            }
        })
        
        // Add auto-lookup on barcode input
        binding.editTextBarcode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val barcode = s.toString().trim()
                if (barcode.length == 13 && barcode.startsWith("69") && binding.switchApiLookup.isChecked) {
                    lookupBarcodeInfo(barcode)
                }
            }
        })
    }

    private fun setupBarcodeReceiver() {
        // Listen for barcode scan events
        BarcodeEvent.barcodeData.observe(this) { barcode ->
            if (!barcode.isNullOrEmpty()) {
                Log.d(TAG, "BarcodeEvent received: $barcode")
                
                // Only play a sound if one hasn't been played already for this barcode
                if (!BarcodeEvent.hasSoundBeenPlayed()) {
                    Log.d(TAG, "Playing sound for barcode: $barcode")
                    SoundUtil.playScanBeep()
                    BarcodeEvent.markSoundPlayed()
                }
                
                // Set the barcode field
                binding.editTextBarcode.setText(barcode)
                binding.editTextBarcode.requestFocus()  // Ensure focus stays on barcode field
                Toast.makeText(this, getString(R.string.barcode_field_filled), Toast.LENGTH_SHORT).show()
                
                // Look up product info if barcode is from a public database (starts with '69')
                // and API lookup is enabled
                if (barcode.startsWith("69") && barcode.length == 13 && binding.switchApiLookup.isChecked) {
                    lookupBarcodeInfo(barcode)
                }
                
                // Clear the scanned barcode to prevent duplicate processing
                BarcodeEvent.clearLastBarcode()
            }
        }
    }

    fun lookupBarcodeInfo(barcode: String) {
        // Clear the barcode event data to prevent reprocessing
        BarcodeEvent.clearLastBarcode()
        
        // Check if API lookup is enabled
        if (!binding.switchApiLookup.isChecked) {
            Log.d(TAG, "API lookup is disabled, skipping lookup for barcode: $barcode")
            return
        }
        
        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE
        
        // Check if AppCode is set
        val appCode = preferencesManager.getBarcodeAppCode()
        if (appCode.isNullOrEmpty()) {
            binding.progressBar.visibility = View.GONE
            showAppCodeDialog()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://ali-barcode.showapi.com/barcode?code=$barcode")
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "APPCODE $appCode")
                
                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    
                    if (jsonObject.getInt("showapi_res_code") == 0) {
                        val resBody = jsonObject.getJSONObject("showapi_res_body")
                        
                        // Extract product information
                        val productName = resBody.optString("goodsName", "")
                        val spec = resBody.optString("spec", "")
                        val price = resBody.optString("price", "0.0")
                        val imgUrl = resBody.optString("img", "")
                        
                        withContext(Dispatchers.Main) {
                            // Update UI with the information
                            if (productName.isNotEmpty()) {
                                binding.editTextName.setText(productName)
                            }
                            if (spec.isNotEmpty()) {
                                binding.editTextSpecs.setText(spec)
                            }
                            if (price != "0.0") {
                                binding.editTextCost.setText(price)
                            }
                            if (imgUrl.isNotEmpty()) {
                                binding.editTextLink.setText(imgUrl)
                            }
                            
                            binding.progressBar.visibility = View.GONE
                            SoundUtil.playScanBeep()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(
                                this@AddProductActivity, 
                                "Failed to get product info: ${jsonObject.optString("showapi_res_error", "Unknown error")}", 
                                Toast.LENGTH_LONG
                            ).show()
                            SoundUtil.playScanBeep()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(
                            this@AddProductActivity, 
                            "Failed to get product info: HTTP $responseCode", 
                            Toast.LENGTH_LONG
                        ).show()
                        SoundUtil.playScanBeep()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@AddProductActivity, 
                        "Error looking up barcode: ${e.message}", 
                        Toast.LENGTH_LONG
                    ).show()
                    SoundUtil.playScanBeep()
                    Log.e(TAG, "Error during barcode lookup", e)
                }
            }
        }
    }

    private fun showAppCodeDialog() {
        // Show dialog to enter APPCODE
        val dialog = AppCodeDialogFragment()
        dialog.show(supportFragmentManager, "AppCodeDialog")
    }

    private fun saveProduct() {
        // Get field values
        val barcode = binding.editTextBarcode.text.toString().trim()
        val name = binding.editTextName.text.toString().trim()
        val specs = binding.editTextSpecs.text.toString().trim()
        val costStr = binding.editTextCost.text.toString().trim()
        val stockStr = binding.editTextStock.text.toString().trim()
        val location = binding.editTextLocation.text.toString().trim()
        val link = binding.editTextLink.text.toString().trim()

        // Validate required fields - product code, barcode and specs are now optional
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a product name", Toast.LENGTH_SHORT).show()
            return
        }

        // Parse numeric values
        val cost = if (costStr.isNotEmpty()) costStr.toFloatOrNull() else null
        val stock = if (stockStr.isNotEmpty()) stockStr.toIntOrNull() else null

        if (costStr.isNotEmpty() && cost == null) {
            Toast.makeText(this, "Please enter a valid cost value", Toast.LENGTH_SHORT).show()
            return
        }

        if (stockStr.isNotEmpty() && stock == null) {
            Toast.makeText(this, "Please enter a valid stock value", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading indicator
        binding.progressBar.visibility = View.VISIBLE

        // Use barcode as product code identifier if available
        // If not provided, the server will generate a unique code
        val productCode = if (barcode.isNotEmpty()) barcode else null
        
        val product = Product(
            code = productCode, // Server will handle empty code generation
            name = name,
            description = specs,
            quantity = stock,
            location = if (location.isEmpty()) null else location,
            cost = cost,
            link = if (link.isEmpty()) null else link
        )
        
        // Submit product to API
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = NetworkModule.stockSyncApi.addProduct(product)
                
                if (response.isSuccessful) {
                    // Get the product code from the response or use barcode
                    val responseBody = response.body()
                    val responseCode = if (responseBody != null && responseBody.data is Map<*, *>) {
                        responseBody.data.let { data ->
                            (data as? Map<*, *>)?.get("code")?.toString() ?: barcode
                        }
                    } else {
                        barcode // Fallback to barcode if we can't extract the code
                    }
                    
                    // If we have images, upload them
                    if (imageFiles.isNotEmpty()) {
                        uploadProductImages(responseCode)
                    } else {
                        // No images to upload, just finish
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.buttonSave.isEnabled = true
                            Toast.makeText(this@AddProductActivity, "Product added successfully", Toast.LENGTH_SHORT).show()
                            SoundUtil.playScanBeep()
                            finish()
                        }
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonSave.isEnabled = true
                        Toast.makeText(this@AddProductActivity, "Failed to add product: $errorBody", Toast.LENGTH_LONG).show()
                        SoundUtil.playScanBeep()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSave.isEnabled = true
                    Toast.makeText(this@AddProductActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                    SoundUtil.playScanBeep()
                }
            } catch (e: HttpException) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSave.isEnabled = true
                    Toast.makeText(this@AddProductActivity, "HTTP error: ${e.message}", Toast.LENGTH_LONG).show()
                    SoundUtil.playScanBeep()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSave.isEnabled = true
                    Toast.makeText(this@AddProductActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    SoundUtil.playScanBeep()
                    Log.e(TAG, "Error saving product", e)
                }
            }
        }
    }
    
    // Upload product images to the API
    private fun uploadProductImages(productCode: String) {
        Log.d(TAG, "Starting to upload ${imageFiles.size} images for product code: $productCode")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Convert images to base64
                val base64Images = mutableListOf<String>()
                for (file in imageFiles) {
                    val base64 = ImageUtil.fileToBase64DataUri(file)
                    if (base64 != null) {
                        base64Images.add(base64)
                        Log.d(TAG, "Successfully converted image to base64: ${file.name}, length: ${base64.length}")
                    } else {
                        Log.e(TAG, "Failed to convert image to base64: ${file.absolutePath}")
                    }
                }
                
                if (base64Images.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@AddProductActivity, "Failed to process images", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // Create request body
                val requestBody = mapOf(
                    "code" to productCode,
                    *base64Images.mapIndexed { index, base64 -> 
                        "image${index + 1}" to base64 
                    }.toTypedArray()
                )
                
                // Upload images
                val response = NetworkModule.stockSyncApi.uploadProductImages(requestBody)
                
                // Handle response on main thread
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (response.isSuccessful) {
                        // Success
                        Toast.makeText(this@AddProductActivity, "Product added and images uploaded successfully", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        // Error
                        Toast.makeText(this@AddProductActivity, "Failed to upload images: ${response.message()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading images: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@AddProductActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Create a unique file for storing the image
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "PRODUCT_${timeStamp}_"
        
        // Try multiple storage options
        var storageDir: File? = null
        
        // First try external files directory
        val externalFilesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (externalFilesDir != null && (externalFilesDir.exists() || externalFilesDir.mkdirs())) {
            storageDir = externalFilesDir
            Log.d(TAG, "Using external files directory: ${externalFilesDir.absolutePath}")
        } 
        // If that fails, try internal files directory
        else {
            val internalDir = File(filesDir, "Pictures")
            if (internalDir.exists() || internalDir.mkdirs()) {
                storageDir = internalDir
                Log.d(TAG, "Using internal files directory: ${internalDir.absolutePath}")
            } else {
                // Last resort - use cache directory
                val cacheDir = File(cacheDir, "Pictures")
                if (cacheDir.exists() || cacheDir.mkdirs()) {
                    storageDir = cacheDir
                    Log.d(TAG, "Using cache directory: ${cacheDir.absolutePath}")
                } else {
                    throw IOException("Failed to create directory for saving images")
                }
            }
        }
        
        // Ensure the ProductImages subdirectory exists
        val imageDir = File(storageDir, "ProductImages")
        if (!imageDir.exists() && !imageDir.mkdirs()) {
            Log.w(TAG, "Failed to create ProductImages directory, using parent directory")
            // If we can't create the subdirectory, just use the parent directory
        } else {
            storageDir = imageDir
        }
        
        Log.d(TAG, "Final image directory: ${storageDir.absolutePath}")
        
        // Create the file
        val image = File.createTempFile(
            imageFileName,  /* prefix */
            ".jpg",         /* suffix */
            storageDir      /* directory */
        )
        
        // Save the file path for later use
        currentPhotoPath = image.absolutePath
        Log.d(TAG, "Created image file at: $currentPhotoPath")
        return image
    }
    
    // Launch the camera intent with better error handling
    private fun dispatchTakePictureIntent() {
        try {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            
            // Create the File where the photo should go
            val photoFile: File
            try {
                photoFile = createImageFile()
                Log.d(TAG, "Photo file created at: ${photoFile.absolutePath}")
            } catch (ex: IOException) {
                // Error occurred while creating the File
                Log.e(TAG, "Error creating image file", ex)
                Toast.makeText(this, getString(R.string.could_not_create_image), Toast.LENGTH_SHORT).show()
                return
            }
            
            // Continue with the created file
            try {
                val photoURI = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                
                Log.d(TAG, "Photo URI created: $photoURI")
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                
                // Grant permissions for the URI
                takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                // Fallback check for camera app
                val cameraActivities = packageManager.queryIntentActivities(
                    takePictureIntent, PackageManager.MATCH_DEFAULT_ONLY
                )
                
                if (cameraActivities.isNotEmpty()) {
                    // Grant permission to each camera app
                    for (resolveInfo in cameraActivities) {
                        val packageName = resolveInfo.activityInfo.packageName
                        grantUriPermission(
                            packageName,
                            photoURI,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    
                    Log.d(TAG, "Launching camera with URI: $photoURI")
                    takePictureLauncher.launch(takePictureIntent)
                } else {
                    Log.e(TAG, "No camera app found")
                    Toast.makeText(this, getString(R.string.no_camera_app), Toast.LENGTH_SHORT).show()
                    
                    // Try a simple intent without URI as last resort
                    try {
                        Log.d(TAG, "Attempting fallback to basic camera capture")
                        takePictureLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                    } catch (e2: Exception) {
                        Log.e(TAG, "Even fallback camera capture failed", e2)
                        Toast.makeText(this, getString(R.string.failed_launch_camera), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up camera intent", e)
                Toast.makeText(this, getString(R.string.camera_setup_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in dispatchTakePictureIntent", e)
            Toast.makeText(this, getString(R.string.error_launching_camera), Toast.LENGTH_SHORT).show()
        }
    }
    
    // Update the image gallery with thumbnails
    private fun updateImageGallery() {
        // Clear existing thumbnails
        binding.imageGalleryContainer.removeAllViews()
        
        // Add thumbnails for each image
        for (file in imageFiles) {
            val thumbnailView = layoutInflater.inflate(R.layout.item_product_image, binding.imageGalleryContainer, false)
            val imageView = thumbnailView.findViewById<ImageView>(R.id.imageProductThumb)
            
            // Load the image from file
            imageView.setImageURI(Uri.fromFile(file))
            
            // Set up delete button
            thumbnailView.findViewById<View>(R.id.buttonDeleteImage).setOnClickListener {
                // Remove this image from our list
                imageFiles.remove(file)
                // Delete the file
                file.delete()
                // Update the gallery
                updateImageGallery()
            }
            
            // Add the thumbnail to the gallery
            binding.imageGalleryContainer.addView(thumbnailView)
        }
        
        // Update the image count using property access
        binding.textImageCount.text = getString(R.string.image_count_format, imageFiles.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up any granted URI permissions
        cleanupUriPermissions()
    }
    
    private fun cleanupUriPermissions() {
        // Revoke permissions for any FileProvider URIs that were granted
        for (file in imageFiles) {
            try {
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.e(TAG, "Error revoking URI permission", e)
            }
        }
    }

    private fun updateMultiCaptureStatus() {
        // This is no longer needed since we're using a custom camera
        // Instead, we'll show a toast with the current count
        Toast.makeText(this, getString(R.string.photos_taken, multiCaptureCount), Toast.LENGTH_SHORT).show()
    }

    // Launch the multi-capture camera activity
    private fun launchMultiCaptureCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, MultiCaptureCameraActivity::class.java)
            multiCaptureLauncher.launch(intent)
        } else {
            requestPermissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
        }
    }

    override fun onResume() {
        super.onResume()
        
        // If returning to the screen and not in active multi-capture, make sure UI is reset
        if (!isMultiCaptureMode) {
            binding.multiCaptureContainer.visibility = View.GONE
            binding.buttonTakePhoto.visibility = View.VISIBLE
        }
    }
} 