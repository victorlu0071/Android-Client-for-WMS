package com.example.myapplication.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityMultiCaptureCameraBinding
import com.example.myapplication.util.PreferencesManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.view.isVisible

class MultiCaptureCameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMultiCaptureCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private val capturedImagePaths = ArrayList<String>()
    private var photoCount = 0
    private var lastPhotoFile: File? = null
    private var camera: androidx.camera.core.Camera? = null
    private var isFocusing = false
    private var scanButtonKeyCode: Int? = KeyEvent.KEYCODE_VOLUME_UP // Default scan button

    companion object {
        private const val TAG = "MultiCaptureCamera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val EXTRA_IMAGE_PATHS = "image_paths"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiCaptureCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If preview is visible, close it instead of exiting the activity
                if (binding.imagePreviewContainer.isVisible) {
                    hideFullScreenPreview()
                } else {
                    finish()
                }
            }
        })

        // Request camera permissions if not already granted
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listeners for capture and done buttons
        binding.captureButton.setOnClickListener { captureImage() }
        binding.doneButton.setOnClickListener { finishWithResult() }
        
        // Set up thumbnail click listener for preview
        binding.lastPhotoThumbnail.setOnClickListener { 
            showFullScreenPreview() 
        }
        
        // Set up close preview button
        binding.closePreviewButton.setOnClickListener {
            hideFullScreenPreview()
        }

        // Set up touch listener for focus
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Get the bound scan button from preferences
                scanButtonKeyCode = PreferencesManager.getInstance(this).getScanButtonKeyCode()
                
                val factory = SurfaceOrientedMeteringPointFactory(
                    binding.viewFinder.width.toFloat(),
                    binding.viewFinder.height.toFloat()
                )
                val point = factory.createPoint(event.x, event.y)
                triggerFocus(point)
                showFocusAnimation(event.x, event.y)
                return@setOnTouchListener true
            }
            false
        }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize photo count
        updatePhotoCount()
    }

    // Handles focusing on a specific point
    private fun triggerFocus(point: MeteringPoint) {
        if (isFocusing) return
        
        isFocusing = true
        
        // Create a focus action with auto-cancel
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()
            
        camera?.cameraControl?.startFocusAndMetering(action)?.addListener({
            isFocusing = false
        }, ContextCompat.getMainExecutor(this))
    }
    
    // Focus at the center of the screen (for physical buttons)
    private fun triggerCenterFocus() {
        if (isFocusing) return
        
        val width = binding.viewFinder.width.toFloat()
        val height = binding.viewFinder.height.toFloat()
        
        val factory = SurfaceOrientedMeteringPointFactory(width, height)
        val centerPoint = factory.createPoint(width / 2, height / 2)
        
        triggerFocus(centerPoint)
        showFocusAnimation(width / 2, height / 2)
    }
    
    // Shows a visual animation at the focus point
    private fun showFocusAnimation(x: Float, y: Float) {
        // Move the focus indicator to the touch point
        binding.focusIndicator.x = x - binding.focusIndicator.width / 2
        binding.focusIndicator.y = y - binding.focusIndicator.height / 2
        
        // Make the indicator visible and animate it
        binding.focusIndicator.visibility = View.VISIBLE
        binding.focusIndicator.alpha = 1f
        
        // Scale down and fade the indicator 
        binding.focusIndicator.animate()
            .scaleX(0.8f)
            .scaleY(0.8f)
            .alpha(0.7f)
            .setDuration(300)
            .withEndAction {
                // Scale back up 
                binding.focusIndicator.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        binding.focusIndicator.visibility = View.INVISIBLE
                        binding.focusIndicator.scaleX = 1f
                        binding.focusIndicator.scaleY = 1f
                    }
                    .start()
            }
            .start()
            
        // Show focusing text
        binding.focusStateText.setText(R.string.focusing)
        binding.focusStateText.visibility = View.VISIBLE
        
        // Hide the text after a delay
        binding.focusStateText.postDelayed({
            binding.focusStateText.visibility = View.INVISIBLE
        }, 1500)
    }
    
    private fun showFullScreenPreview() {
        lastPhotoFile?.let { file ->
            // Load the image into the full-screen view
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            binding.fullScreenImageView.setImageBitmap(bitmap)
            
            // Show the preview container
            binding.imagePreviewContainer.visibility = View.VISIBLE
            
            // Hide camera controls
            binding.controlsContainer.visibility = View.GONE
            binding.lastPhotoThumbnail.visibility = View.GONE
            binding.photoCountText.visibility = View.GONE
        }
    }
    
    private fun hideFullScreenPreview() {
        // Hide the preview container
        binding.imagePreviewContainer.visibility = View.GONE
        
        // Show camera controls again
        binding.controlsContainer.visibility = View.VISIBLE
        binding.lastPhotoThumbnail.visibility = View.VISIBLE
        binding.photoCountText.visibility = View.VISIBLE
    }

    // Handle physical button presses for focusing and capturing
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if we're using the volume button or custom scan button
        if (keyCode == scanButtonKeyCode || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // Don't handle if we're in full screen preview mode
            if (binding.imagePreviewContainer.isVisible) {
                return super.onKeyDown(keyCode, event)
            }
            
            // Trigger focus in the center of the screen
            triggerCenterFocus()
            
            // If this is a long press, also capture a photo
            if (event?.isLongPress == true) {
                captureImage()
            }
            
            return true
        }
        return super.onKeyDown(keyCode, event)
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

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Image capture configuration
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                // Trigger initial focus when camera starts
                binding.viewFinder.post {
                    triggerCenterFocus()
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage() {
        // Get a stable reference of the image capture use case
        val imageCapture = imageCapture ?: return

        // Create file for the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Show saving indicator
        binding.savingProgressBar.visibility = View.VISIBLE

        // Take the picture
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    binding.savingProgressBar.visibility = View.GONE
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = getString(R.string.photo_saved)
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Photo saved: $savedUri")

                    // Add path to our list
                    capturedImagePaths.add(photoFile.absolutePath)
                    photoCount++
                    updatePhotoCount()

                    // Save reference to the last photo taken
                    lastPhotoFile = photoFile
                    
                    // Update thumbnail
                    updateThumbnail(photoFile)

                    // Hide progress
                    binding.savingProgressBar.visibility = View.GONE
                }
            }
        )
    }

    private fun updateThumbnail(file: File) {
        // Create thumbnail from saved file and display
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        binding.lastPhotoThumbnail.setImageBitmap(bitmap)
        binding.lastPhotoThumbnail.visibility = View.VISIBLE
    }

    private fun updatePhotoCount() {
        binding.photoCountText.text = getString(R.string.photos_taken, photoCount)
    }

    private fun finishWithResult() {
        // Hide preview if it's visible
        if (binding.imagePreviewContainer.isVisible) {
            hideFullScreenPreview()
            return
        }
        
        val resultIntent = Intent()
        resultIntent.putStringArrayListExtra(EXTRA_IMAGE_PATHS, capturedImagePaths)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "ProductImages").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
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
                // Return empty result if permissions denied
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
} 