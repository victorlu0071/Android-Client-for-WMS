package com.example.myapplication.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityMultiCaptureCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MultiCaptureCameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMultiCaptureCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private val capturedImagePaths = ArrayList<String>()
    private var photoCount = 0
    private var lastPhotoFile: File? = null

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
                if (binding.imagePreviewContainer.visibility == View.VISIBLE) {
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

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize photo count
        updatePhotoCount()
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
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

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
        if (binding.imagePreviewContainer.visibility == View.VISIBLE) {
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