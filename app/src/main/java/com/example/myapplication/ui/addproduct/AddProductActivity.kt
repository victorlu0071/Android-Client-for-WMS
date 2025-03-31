package com.example.myapplication.ui.addproduct

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
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
    private val imageFiles = mutableListOf<File>()
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

        // Listen for barcode scan results
        BarcodeEvent.barcodeData.observe(this) { barcode ->
            if (barcode.isNotEmpty()) {
                binding.editTextCode.setText(barcode)
                
                // Check if barcode starts with 69 and has 13 digits
                if (barcode.startsWith("69") && barcode.length == 13) {
                    lookupBarcodeInfo(barcode)
                }
            }
        }

        // Set up click listeners
        binding.buttonScanBarcode.setOnClickListener {
            BarcodeEvent.clearLastBarcode()
            val intent = Intent(this, CustomBarcodeScannerActivity::class.java)
            startActivity(intent)
        }

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
    }

    // Add support for physical scan button
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if this key matches the bound scan button
        val boundScanKey = preferencesManager.getScanButtonKeyCode()
        
        if (boundScanKey != null && keyCode == boundScanKey) {
            // Launch the barcode scanner
            BarcodeEvent.clearLastBarcode()
            val intent = Intent(this, CustomBarcodeScannerActivity::class.java)
            startActivity(intent)
            return true
        }
        
        return super.onKeyDown(keyCode, event)
    }

    fun lookupBarcodeInfo(barcode: String) {
        // Clear the barcode event data to prevent reprocessing
        BarcodeEvent.clearLastBarcode()
        
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
        // Validate required fields
        val code = binding.editTextCode.text.toString().trim()
        val name = binding.editTextName.text.toString().trim()
        val specs = binding.editTextSpecs.text.toString().trim()
        
        if (TextUtils.isEmpty(code) || TextUtils.isEmpty(name) || TextUtils.isEmpty(specs)) {
            Toast.makeText(this, "Code, name, and specs are required", Toast.LENGTH_SHORT).show()
            SoundUtil.playScanBeep()
            return
        }
        
        // Optional fields
        val costText = binding.editTextCost.text.toString().trim()
        val cost = if (costText.isNotEmpty()) costText.toFloatOrNull() else null
        val location = binding.editTextLocation.text.toString().trim().takeIf { it.isNotEmpty() }
        val stockText = binding.editTextStock.text.toString().trim()
        val stock = if (stockText.isNotEmpty()) stockText.toIntOrNull() else null
        val link = binding.editTextLink.text.toString().trim().takeIf { it.isNotEmpty() }
        
        // Cost validation
        if (costText.isNotEmpty() && cost == null) {
            Toast.makeText(this, "Invalid cost value", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Stock validation
        if (stockText.isNotEmpty() && stock == null) {
            Toast.makeText(this, "Invalid stock value", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create product object
        val product = Product(
            code = code,
            name = name,
            description = specs,
            quantity = stock,
            location = location,
            cost = cost,
            link = link
        )
        
        // Show loading
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSave.isEnabled = false
        
        // Submit product to API
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = NetworkModule.stockSyncApi.addProduct(product)
                
                if (response.isSuccessful) {
                    // If we have images, upload them
                    if (imageFiles.isNotEmpty()) {
                        uploadProductImages(code)
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
    private suspend fun uploadProductImages(productCode: String) {
        try {
            // Show progress indicator
            binding.progressBar.visibility = View.VISIBLE
            
            // Convert each image file to base64 encoded string
            val base64Images = mutableListOf<String>()
            val imagesRequest = mutableMapOf<String, String>()
            
            // Set the product code
            imagesRequest["code"] = productCode
            
            // Convert each image to base64 and add to the request
            imageFiles.forEachIndexed { index, file ->
                Log.d(TAG, "Converting image ${index + 1} to base64: ${file.absolutePath}")
                
                // Convert file to base64 data URI
                val base64 = ImageUtil.fileToBase64DataUri(file)
                if (base64 != null) {
                    // Add to the request with key "image1", "image2", etc.
                    val imageKey = "image${index + 1}"
                    imagesRequest[imageKey] = base64
                    Log.d(TAG, "Added image $imageKey (${base64.length} chars)")
                } else {
                    Log.e(TAG, "Failed to convert image ${index + 1} to base64")
                }
            }
            
            // Upload the images if we have any
            if (imagesRequest.size > 1) { // > 1 because we have the code field
                Log.d(TAG, "Uploading ${imagesRequest.size - 1} images for product $productCode")
                val response = NetworkModule.stockSyncApi.uploadProductImages(imagesRequest)
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSave.isEnabled = true
                    
                    if (response.isSuccessful) {
                        Toast.makeText(this@AddProductActivity, "Product and images saved successfully", Toast.LENGTH_SHORT).show()
                        SoundUtil.playScanBeep()
                        finish()
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Toast.makeText(
                            this@AddProductActivity,
                            "Product saved but image upload failed: $errorBody",
                            Toast.LENGTH_LONG
                        ).show()
                        SoundUtil.playScanBeep()
                        finish()
                    }
                }
            } else {
                // No images to upload
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSave.isEnabled = true
                    Toast.makeText(this@AddProductActivity, "Product saved but no images were uploaded", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                Toast.makeText(
                    this@AddProductActivity,
                    "Product saved but image upload failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                SoundUtil.playScanBeep()
                Log.e(TAG, "Error uploading images", e)
                finish()
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

    // Check permissions before taking a photo
    private fun checkPermissionsAndTakePhoto() {
        try {
            // If we're already in multi-capture mode and have taken at least one photo,
            // we can skip the permission check as we already have the necessary permissions
            if (isMultiCaptureMode && multiCaptureCount > 0) {
                dispatchTakePictureIntent()
                return
            }
            
            // Regular permission flow for first photo
            // Check for CAMERA permission first (most critical)
            val cameraPermission = android.Manifest.permission.CAMERA
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                this, 
                cameraPermission
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "Camera permission status: ${if (hasCameraPermission) "GRANTED" else "DENIED"}")
            
            // If we have camera permission, determine if we need storage permissions based on Android version
            if (hasCameraPermission) {
                // On Android 10+ (API 29+), we don't need external storage permissions for app-specific files
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // We have camera permission and don't need storage permission on Android 10+
                    Log.d(TAG, "Running on Android 10+, no storage permissions needed")
                    dispatchTakePictureIntent()
                } else {
                    // Pre-Android 10 needs storage permissions
                    val writePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    val readPermission = android.Manifest.permission.READ_EXTERNAL_STORAGE
                    
                    val hasWritePermission = ContextCompat.checkSelfPermission(
                        this, 
                        writePermission
                    ) == PackageManager.PERMISSION_GRANTED
                    
                    val hasReadPermission = ContextCompat.checkSelfPermission(
                        this, 
                        readPermission
                    ) == PackageManager.PERMISSION_GRANTED
                    
                    Log.d(TAG, "Storage permissions: Read=${if (hasReadPermission) "GRANTED" else "DENIED"}, Write=${if (hasWritePermission) "GRANTED" else "DENIED"}")
                    
                    // If any permission is missing, request it
                    if (!hasWritePermission || !hasReadPermission) {
                        Log.d(TAG, "Requesting storage permissions")
                        val permissionsToRequest = mutableListOf<String>()
                        
                        if (!hasWritePermission) permissionsToRequest.add(writePermission)
                        if (!hasReadPermission) permissionsToRequest.add(readPermission)
                        
                        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                    } else {
                        // We have all permissions, proceed with taking photo
                        Log.d(TAG, "All permissions granted, taking photo")
                        dispatchTakePictureIntent()
                    }
                }
            } else {
                // Need to request camera permission
                Log.d(TAG, "Requesting camera permission")
                requestPermissionLauncher.launch(arrayOf(cameraPermission))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            Toast.makeText(this, getString(R.string.error_checking_permissions, e.message), Toast.LENGTH_SHORT).show()
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
        
        // Update the image count
        binding.textImageCount.setText(getString(R.string.image_count_format, imageFiles.size))
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

    private fun exitMultiCaptureMode() {
        // No longer needed as the custom camera handles this
        isMultiCaptureMode = false
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