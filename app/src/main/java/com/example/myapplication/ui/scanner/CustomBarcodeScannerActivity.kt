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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomBarcodeScannerBinding.inflate(layoutInflater)
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
        
        // Set up the scanning instructions
        updateScanInstructions("Align barcode within the frame")
    }
    
    private fun updateScanInstructions(text: String) {
        binding.scanInstructions.text = text
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            
            // Image analysis for barcode scanning
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
                                // Show the scan result animation
                                showScanResultAnimation()
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
                
                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                
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
                triggerFocus(width / 2, height / 2)
            }
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
                
                // Focus action with auto exposure
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .addPoint(point, FocusMeteringAction.FLAG_AE) // Also adjust exposure
                    .setAutoCancelDuration(5, TimeUnit.SECONDS) // Auto cancel after 5 seconds
                    .build()
                
                // Start focusing
                camera.cameraControl.startFocusAndMetering(action)
                
                // Show focus animation
                showFocusAnimation()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error focusing camera: ${e.message}", e)
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
        
        // Update the scanning instruction
        updateScanInstructions("Barcode found: $code")
        
        // Play a beep sound to provide feedback
        SoundUtil.playScanBeep()
        
        // Send result back to the calling activity
        val resultIntent = Intent().apply {
            putExtra(BARCODE_RESULT, code)
        }
        setResult(RESULT_OK, resultIntent)
        
        // Clear any previous barcode data before posting a new result
        BarcodeEvent.clearLastBarcode()
        
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
    
    private inner class BarcodeAnalyzer(private val barcodeListener: (List<Barcode>) -> Unit) : ImageAnalysis.Analyzer {
        
        // Add throttling mechanism
        private var lastAnalyzedTimestamp = 0L
        
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()
            // Only analyze every 300ms to prevent rapid successive scans
            if (currentTimestamp - lastAnalyzedTimestamp >= 300) {
                
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