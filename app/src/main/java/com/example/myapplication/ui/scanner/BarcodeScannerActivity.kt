package com.example.myapplication.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.example.myapplication.databinding.ActivityBarcodeScannerBinding
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

class BarcodeScannerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "BarcodeScannerActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val BARCODE_RESULT = "barcode_result"
    }
    
    private lateinit var binding: ActivityBarcodeScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var preferencesManager: PreferencesManager
    
    // Track the camera instance for auto-focus control
    private var camera: Camera? = null
    
    // Add flag to prevent multiple detections
    private val isScanning = AtomicBoolean(true)
    
    // Track the last successful focus position for future sessions
    private var lastFocusX: Float = 0f
    private var lastFocusY: Float = 0f
    private var hasSavedFocus = false
    
    // Add focus distance tracking
    private var initialFocusComplete = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferencesManager = PreferencesManager.getInstance(applicationContext)
        
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
        
        // Back button
        binding.backButton.setOnClickListener {
            finish()
        }
        
        // Set up touch listener for auto focus with visual indicators
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // For touch, use specific point focus but with visual indicators
                initialFocusComplete = true // Mark focus as complete after manual interaction
                triggerAutoFocus(event.x, event.y)
                
                // Animate scan overlay at touch location
                binding.scanOverlay.animate()
                    .scaleX(0.9f).scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction {
                        binding.scanOverlay.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(200)
                            .start()
                    }
                    .start()
                
                return@setOnTouchListener true
            }
            return@setOnTouchListener false
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Enhanced preview with camera settings for focus
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            
            // Image analyzer for barcode scanning
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcodes ->
                        // Only process if we're still scanning
                        if (isScanning.get() && barcodes.isNotEmpty()) {
                            val barcode = barcodes[0]
                            barcode.rawValue?.let { value ->
                                Log.d(TAG, "Barcode found: $value")
                                // Save the current focus point as successful
                                saveFocusPosition()
                                // Set focus as complete when we successfully read a barcode
                                initialFocusComplete = true
                                // Set flag to prevent further detections
                                if (isScanning.getAndSet(false)) {
                                    returnBarcodeResult(value)
                                }
                            }
                        }
                    })
                }
            
            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                
                // Bind use cases to camera with extended config
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                
                // Configure camera for optimal barcode scanning focus
                setupInitialFocus()
                
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    // Configure camera with optimal focus settings for barcode scanning - fix to prevent auto-exit
    private fun setupInitialFocus() {
        // Wait for view to be properly laid out
        binding.viewFinder.post {
            // First check if we're still active - don't do focus if activity is finishing
            if (isFinishing) return@post
            
            camera?.let { camera ->
                try {
                    // First, cancel any previous focus action
                    camera.cameraControl.cancelFocusAndMetering()
                    
                    // If we have a saved focus position from a previous successful scan
                    if (hasSavedFocus) {
                        Log.d(TAG, "Applying saved focus position: ($lastFocusX, $lastFocusY)")
                        
                        // Create a factory for the saved position
                        val factory = SurfaceOrientedMeteringPointFactory(
                            binding.viewFinder.width.toFloat(),
                            binding.viewFinder.height.toFloat()
                        )
                        
                        // Create a metering point at the saved position
                        val centerPoint = factory.createPoint(lastFocusX, lastFocusY)
                        
                        // Use a simpler initial focus approach to avoid false detection issues
                        val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
                            .addPoint(centerPoint, FocusMeteringAction.FLAG_AE) // Also adjust exposure
                            .disableAutoCancel() // Make focus stick until next request
                            .build()
                        
                        // Apply the focus
                        camera.cameraControl.startFocusAndMetering(action)
                        
                        // Show visual indicator only (without triggering additional focus)
                        binding.scanOverlay.animate()
                            .scaleX(0.9f).scaleY(0.9f)
                            .setDuration(200)
                            .withEndAction {
                                binding.scanOverlay.animate()
                                    .scaleX(1.0f).scaleY(1.0f)
                                    .setDuration(200)
                                    .start()
                            }
                            .start()
                    } else {
                        // No saved position - do a standard center focus
                        val width = binding.viewFinder.width.toFloat()
                        val height = binding.viewFinder.height.toFloat()
                        
                        if (width > 0 && height > 0) {
                            val factory = SurfaceOrientedMeteringPointFactory(width, height)
                            val centerPoint = factory.createPoint(width / 2, height / 2)
                            
                            val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
                                .addPoint(centerPoint, FocusMeteringAction.FLAG_AE)
                                .disableAutoCancel()
                                .build()
                            
                            camera.cameraControl.startFocusAndMetering(action)
                            
                            // Just show the animation - don't trigger additional focus methods
                            binding.scanOverlay.animate()
                                .scaleX(0.9f).scaleY(0.9f)
                                .setDuration(200)
                                .withEndAction {
                                    binding.scanOverlay.animate()
                                        .scaleX(1.0f).scaleY(1.0f)
                                        .setDuration(200)
                                        .start()
                                }
                                .start()
                        }
                    }
                    
                    // Set focus as complete immediately to avoid additional focus attempts
                    initialFocusComplete = true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up initial focus: ${e.message}", e)
                }
            }
        }
    }
    
    // Retrieve saved focus position from preferences when starting
    override fun onStart() {
        super.onStart()
        // Load saved focus position if available
        val sharedPrefs = getSharedPreferences("scanner_settings", MODE_PRIVATE)
        lastFocusX = sharedPrefs.getFloat("last_focus_x", 0f)
        lastFocusY = sharedPrefs.getFloat("last_focus_y", 0f)
        hasSavedFocus = sharedPrefs.getBoolean("has_saved_focus", false)
        
        if (hasSavedFocus) {
            Log.d(TAG, "Loaded saved focus position: ($lastFocusX, $lastFocusY)")
        }
    }
    
    // Save the current focus position for future use
    private fun saveFocusPosition() {
        // Default to center if we don't have specific coordinates
        val x = if (lastFocusX > 0) lastFocusX else binding.viewFinder.width / 2f
        val y = if (lastFocusY > 0) lastFocusY else binding.viewFinder.height / 2f
        
        // Save to preferences
        val sharedPrefs = getSharedPreferences("scanner_settings", MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putFloat("last_focus_x", x)
            putFloat("last_focus_y", y)
            putBoolean("has_saved_focus", true)
            apply()
        }
        
        Log.d(TAG, "Saved focus position: ($x, $y)")
    }
    
    // Trigger auto focus at the center of the preview
    private fun triggerAutoFocus() {
        val width = binding.viewFinder.width.toFloat()
        val height = binding.viewFinder.height.toFloat()
        
        if (width > 0 && height > 0) {
            triggerAutoFocus(width / 2, height / 2)
        } else {
            // If view dimensions aren't ready yet, post a delayed focus
            binding.viewFinder.post {
                triggerAutoFocus()
            }
        }
    }
    
    // Enhanced focus with visual indicators
    private fun triggerFocusWithVisualIndicator() {
        // Show a visual focus indicator (animate scan overlay)
        binding.scanOverlay.animate()
            .scaleX(0.9f).scaleY(0.9f)
            .setDuration(200)
            .withEndAction {
                binding.scanOverlay.animate()
                    .scaleX(1.0f).scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
            
        // Do the actual camera focus
        triggerAutoFocus()
    }
    
    // Trigger auto focus at the specified point
    private fun triggerAutoFocus(x: Float, y: Float) {
        Log.d(TAG, "Triggering auto focus at ($x, $y)")
        
        // Store the focus point for future reference
        lastFocusX = x
        lastFocusY = y
        
        camera?.let { camera ->
            try {
                // Create a point factory using the specified tap point
                val factory = SurfaceOrientedMeteringPointFactory(
                    binding.viewFinder.width.toFloat(),
                    binding.viewFinder.height.toFloat()
                )
                
                // Create a metering point
                val point = factory.createPoint(x, y)
                
                // Enhanced focus action with more aggressive parameters
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .addPoint(point, FocusMeteringAction.FLAG_AE) // Also adjust exposure
                    .disableAutoCancel() // Make focus stick until next request
                    .build()
                
                // Start focusing with clear logging but without side effects that could exit the scanner
                Log.d(TAG, "Starting focus metering at ($x, $y)")
                camera.cameraControl.startFocusAndMetering(action)
                    .addListener({
                        Log.d(TAG, "Focus completed at ($x, $y)")
                        // Mark focus as complete but don't trigger any actions that might exit
                        initialFocusComplete = true
                    }, ContextCompat.getMainExecutor(this))
                
                // Give haptic feedback
                binding.viewFinder.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                
                // Save focus position without triggering any detection/exit flow
                saveFocusPositionSilently(x, y)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error focusing camera: ${e.message}", e)
                // Fallback to simple focus
                camera.cameraControl.cancelFocusAndMetering()
            }
        }
    }
    
    // Rename to better describe that this won't trigger any side effects
    private fun saveFocusPositionSilently(x: Float, y: Float) {
        val sharedPrefs = getSharedPreferences("scanner_settings", MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putFloat("last_focus_x", x)
            putFloat("last_focus_y", y) 
            putBoolean("has_saved_focus", true)
            apply()
        }
    }
    
    // Also update the onStop method to ensure we save focus settings before leaving
    override fun onStop() {
        super.onStop()
        // If we had a successful focus during this session, make sure it's saved
        if (initialFocusComplete) {
            saveFocusPosition()
        }
    }
    
    // Handle physical button press for explicit focus with visual indicators
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if this key matches the bound scan button
        val boundScanKey = preferencesManager.getScanButtonKeyCode()
        
        if (boundScanKey != null && keyCode == boundScanKey) {
            // Direct focus instead of calling methods that might trigger auto-exit
            val width = binding.viewFinder.width.toFloat()
            val height = binding.viewFinder.height.toFloat()
            
            if (width > 0 && height > 0) {
                // Do direct focus at center
                triggerAutoFocus(width / 2, height / 2)
                
                // Just show visual feedback
                binding.scanOverlay.animate()
                    .scaleX(0.9f).scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction {
                        binding.scanOverlay.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(200)
                            .start()
                    }
                    .start()
            }
            return true
        }
        
        // Also trigger on volume buttons as a convenience
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Direct focus instead of calling methods that might trigger auto-exit
            val width = binding.viewFinder.width.toFloat()
            val height = binding.viewFinder.height.toFloat()
            
            if (width > 0 && height > 0) {
                // Do direct focus at center
                triggerAutoFocus(width / 2, height / 2)
                
                // Just show visual feedback
                binding.scanOverlay.animate()
                    .scaleX(0.9f).scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction {
                        binding.scanOverlay.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(200)
                            .start()
                    }
                    .start()
            }
            return true
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    private fun returnBarcodeResult(code: String) {
        Log.d(TAG, "Processing barcode result: $code")
        
        // Play a beep sound to provide feedback
        SoundUtil.playScanBeep()
        
        // Send result back to the calling activity if it's waiting for result
        val resultIntent = Intent().apply {
            putExtra(BARCODE_RESULT, code)
        }
        setResult(RESULT_OK, resultIntent)
        
        // Clear any previous barcode data before posting a new result
        BarcodeEvent.clearLastBarcode()
        
        // Also broadcast the result via the new event system
        BarcodeEvent.postBarcodeResult(code)
        
        finish()
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
                    "Permissions not granted by the user.",
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
    
    private inner class BarcodeAnalyzer(private val barcodeListener: (List<Barcode>) -> Unit) : ImageAnalysis.Analyzer {
        
        // Add throttling mechanism
        private var lastAnalyzedTimestamp = 0L
        
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()
            // Only analyze every 500ms to prevent rapid successive scans
            if (currentTimestamp - lastAnalyzedTimestamp >= 500) {
                
                val mediaImage = imageProxy.image
                if (mediaImage != null && isScanning.get()) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    
                    barcodeScanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            // Only process non-empty barcode results when still scanning
                            if (barcodes.isNotEmpty() && isScanning.get()) {
                                val barcode = barcodes[0]
                                // Make sure we have an actual non-empty barcode value
                                if (!barcode.rawValue.isNullOrEmpty()) {
                                    barcodeListener(barcodes)
                                    lastAnalyzedTimestamp = currentTimestamp
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Barcode scanning failed: ${e.message}", e)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            } else {
                imageProxy.close()
            }
        }
    }
} 