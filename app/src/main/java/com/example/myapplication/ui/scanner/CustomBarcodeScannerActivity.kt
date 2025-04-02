package com.example.myapplication.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityCustomBarcodeScannerBinding
import com.example.myapplication.util.BarcodeEvent
import com.example.myapplication.util.PreferencesManager
import com.example.myapplication.util.SoundUtil
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CustomBarcodeScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CustomBarcodeScanner"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val BARCODE_RESULT = "barcode_result"
    }

    private lateinit var binding: ActivityCustomBarcodeScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var preferencesManager: PreferencesManager
    
    // Track the camera instance for auto-focus control
    private var camera: Camera? = null
    
    // Add flag to prevent multiple detections
    private val isScanning = AtomicBoolean(true)
    
    // Check if we're scanning for the barcode field
    private var scanningForBarcode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomBarcodeScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferencesManager = PreferencesManager.getInstance(applicationContext)
        
        // Check if we're scanning for the barcode field
        scanningForBarcode = intent.getBooleanExtra("SCAN_FOR_BARCODE", false)
        
        // Update UI based on scan mode
        if (scanningForBarcode) {
            binding.scannerTitle.text = "Scan Product Barcode"
            binding.scanInstructions.text = getString(R.string.scan_product_barcode_instruction)
            // Add a subtle purple tint to the scanner for barcode mode
            binding.scanOverlay.setColorFilter(android.graphics.Color.parseColor("#224B0082"))
        } else {
            binding.scannerTitle.text = getString(R.string.scanner_title)
            binding.scanInstructions.text = getString(R.string.scan_barcode_instruction)
            // Clear any tint
            binding.scanOverlay.clearColorFilter()
        }
        
        // Set up the barcode scanner options with enhanced configuration
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .enableAllPotentialBarcodes() // Enable all potential barcode formats for better detection
            .build()
        
        // Initialize the barcode scanner
        barcodeScanner = BarcodeScanning.getClient(options)
        
        // Check camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        
        // Set up our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Set up close button
        binding.closeButton.setOnClickListener {
            finish()
        }
        
        // Handle focus on touch
        binding.viewFinder.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                triggerFocus(event.x, event.y)
                view.performClick()
                return@setOnTouchListener true
            }
            return@setOnTouchListener false
        }
    }
    
    private fun updateScanInstructions(text: String) {
        binding.scanInstructions.text = text
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Configure preview with barcode-optimized settings
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            
            // Configure image analyzer for barcode scanning
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(imageProxy: ImageProxy) {
                            processImageForBarcodes(imageProxy)
                        }
                    })
                }
            
            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                
                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                
                // Configure camera with optimal settings right away
                camera?.let {
                    // Pre-configure camera for best barcode scanning performance
                    configureCameraForBarcodeScanning(it)
                }
                
                // Set initial focus for better scanning
                setInitialFocus()
                
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun setInitialFocus() {
        // Wait for view to be properly laid out
        binding.viewFinder.post {
            val width = binding.viewFinder.width.toFloat()
            val height = binding.viewFinder.height.toFloat()
            
            if (width > 0 && height > 0) {
                // Apply optimized multi-point focus for barcode scanning
                applyOptimizedBarcodeMultiPointFocus(width, height)
            }
        }
    }
    
    // New optimized multi-point focus method for faster focusing on barcodes
    private fun applyOptimizedBarcodeMultiPointFocus(width: Float, height: Float) {
        camera?.let { camera ->
            try {
                // Create a point factory
                val factory = SurfaceOrientedMeteringPointFactory(width, height)
                
                // Create multiple focus points - this helps camera find focus faster
                // by giving it more information about the scene
                val centerPoint = factory.createPoint(width * 0.5f, height * 0.5f)
                val topPoint = factory.createPoint(width * 0.5f, height * 0.4f)
                val bottomPoint = factory.createPoint(width * 0.5f, height * 0.6f)
                val leftPoint = factory.createPoint(width * 0.4f, height * 0.5f)
                val rightPoint = factory.createPoint(width * 0.6f, height * 0.5f)
                
                // Build action with multiple focus points to help camera
                // determine correct focus distance faster
                val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
                    .addPoint(topPoint, FocusMeteringAction.FLAG_AF) // Top focus point
                    .addPoint(bottomPoint, FocusMeteringAction.FLAG_AF) // Bottom focus point
                    .addPoint(leftPoint, FocusMeteringAction.FLAG_AF) // Left focus point
                    .addPoint(rightPoint, FocusMeteringAction.FLAG_AF) // Right focus point
                    .addPoint(centerPoint, FocusMeteringAction.FLAG_AE) // Center point for exposure
                    .build()
                
                // Start focus and metering
                camera.cameraControl.startFocusAndMetering(action)
                    .addListener({
                        // Once focus is complete, fine-tune with close-range focus
                        applyBarcodeOptimizedSettings(camera)
                    }, ContextCompat.getMainExecutor(this))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error applying multi-point focus: ${e.message}", e)
                // Fallback to simple focus if multi-point focus fails
                triggerFocus(width / 2, height / 2)
            }
        }
    }
    
    // New method for barcode optimized camera settings
    private fun applyBarcodeOptimizedSettings(camera: Camera) {
        try {
            // Set slight zoom to help with focus
            camera.cameraControl.setLinearZoom(0.1f)
            
            // Apply a more targeted focus for barcode distance (typically 20-40cm)
            val width = binding.viewFinder.width.toFloat()
            val height = binding.viewFinder.height.toFloat()
            
            if (width <= 0 || height <= 0) return
            
            val factory = SurfaceOrientedMeteringPointFactory(width, height)
            val centerPoint = factory.createPoint(width * 0.5f, height * 0.5f)
            
            // Focus action optimized for barcode scanning distance
            val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
                .addPoint(centerPoint, FocusMeteringAction.FLAG_AE) // Also adjust exposure
                .disableAutoCancel() // Keep focus locked
                .build()
            
            // Apply optimized focus
            camera.cameraControl.startFocusAndMetering(action)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying barcode optimized settings: ${e.message}", e)
        }
    }
    
    private fun triggerFocus(x: Float, y: Float) {
        camera?.let { camera ->
            try {
                // Create a point factory using the view finder dimensions
                val factory = SurfaceOrientedMeteringPointFactory(
                    binding.viewFinder.width.toFloat(),
                    binding.viewFinder.height.toFloat()
                )
                
                // Create a metering point
                val point = factory.createPoint(x, y)
                
                // Improved focus action with quicker response for barcode scanning
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .addPoint(point, FocusMeteringAction.FLAG_AE) // Also adjust exposure
                    .disableAutoCancel() // Make focus stick until next request
                    .build()
                
                // Apply focus and metering
                camera.cameraControl.startFocusAndMetering(action)
                    .addListener({
                        // Once focus is complete, fine-tune with close-range focus
                        applyBarcodeOptimizedSettings(camera)
                    }, ContextCompat.getMainExecutor(this))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error focusing: ${e.message}", e)
            }
        }
    }
    
    private fun showFocusAnimation() {
        // Animate scan overlay as visual feedback
        binding.scanOverlay.animate()
            .scaleX(0.9f).scaleY(0.9f)
            .alpha(0.7f)
            .setDuration(200)
            .withEndAction {
                binding.scanOverlay.animate()
                    .scaleX(1.0f).scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }
    
    private fun showScanResultAnimation() {
        // Show a success indicator animation
        binding.scanSuccessIndicator.visibility = View.VISIBLE
        binding.scanSuccessIndicator.alpha = 0f
        binding.scanSuccessIndicator.scaleX = 0.5f
        binding.scanSuccessIndicator.scaleY = 0.5f
        
        binding.scanSuccessIndicator.animate()
            .alpha(1f)
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(300)
            .withEndAction {
                binding.scanSuccessIndicator.animate()
                    .alpha(0f)
                    .scaleX(1.5f)
                    .scaleY(1.5f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }
    
    // Handle physical button press for explicit focus
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if this key matches the bound scan button
        val boundScanKey = preferencesManager.getScanButtonKeyCode()
        
        if (boundScanKey != null && keyCode == boundScanKey) {
            // Trigger center focus
            val width = binding.viewFinder.width.toFloat()
            val height = binding.viewFinder.height.toFloat()
            
            if (width > 0 && height > 0) {
                triggerFocus(width / 2, height / 2)
            }
            return true
        }
        
        // Handle volume buttons as focus triggers
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val width = binding.viewFinder.width.toFloat()
            val height = binding.viewFinder.height.toFloat()
            
            if (width > 0 && height > 0) {
                triggerFocus(width / 2, height / 2)
            }
            return true
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    private fun returnBarcodeResult(code: String) {
        Log.d(TAG, "Processing barcode result: $code")
        
        // Update the scanning instruction based on mode
        if (scanningForBarcode) {
            updateScanInstructions(getString(R.string.product_barcode_found, code))
        } else {
            updateScanInstructions(getString(R.string.product_code_found, code))
        }
        
        // Play a beep sound to provide feedback
        // NOTE: We're intentionally playing the sound here only, not letting BarcodeEvent do it too
        // to avoid double beeps
        SoundUtil.playScanBeep()
        
        // Send result back to the calling activity
        val resultIntent = Intent().apply {
            putExtra(BARCODE_RESULT, code)
            putExtra("SCAN_FOR_BARCODE", scanningForBarcode)
        }
        setResult(RESULT_OK, resultIntent)
        
        // Clear any previous barcode data before posting a new result
        BarcodeEvent.clearLastBarcode()
        
        // Mark that we've already played a sound for this barcode
        BarcodeEvent.markSoundPlayed()
        
        // Also broadcast the result via the event system
        BarcodeEvent.postBarcodeResult(code)
        
        // Delay closing to show the result animation
        binding.viewFinder.postDelayed({
            finish()
        }, 500)
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, 
        permissions: Array<String>, 
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    
    private fun processImageForBarcodes(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isScanning.get()) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // Process image with barcode scanner
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty() && isScanning.get()) {
                        val barcode = barcodes[0]
                        // Make sure we have an actual non-empty barcode value
                        if (!barcode.rawValue.isNullOrEmpty()) {
                            Log.d(TAG, "Barcode found: ${barcode.rawValue}")
                            // Show the scan result animation
                            showScanResultAnimation()
                            // Set flag to prevent further detections
                            if (isScanning.getAndSet(false)) {
                                returnBarcodeResult(barcode.rawValue!!)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed: ${e.message}", e)
                }
                .addOnCompleteListener {
                    // Always close the image proxy when done
                    imageProxy.close()
                }
        } else {
            // Close the image proxy if we're not processing it
            imageProxy.close()
        }
    }
    
    // New method to configure camera for barcode scanning
    private fun configureCameraForBarcodeScanning(camera: Camera) {
        try {
            // Turn off auto-flash for more consistent focusing
            camera.cameraControl.enableTorch(false)
            
            // Apply a slight zoom to help focus
            camera.cameraControl.setLinearZoom(0.1f)
            
            // No need to check for extended features since we're using simpler approach
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring camera for barcode scanning: ${e.message}", e)
        }
    }
} 