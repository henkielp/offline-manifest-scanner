package com.example.manifestscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.example.manifestscanner.databinding.ActivityMainBinding



import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single Activity for the Offline Manifest & Barcode Sync v2.0 app.
 *
 * Responsibilities:
 *   - Manages CameraX lifecycle with two modes (capture for OCR, scan for barcodes).
 *   - Observes [ManifestViewModel.state] and toggles UI visibility accordingly.
 *   - Bridges ML Kit results into the ViewModel.
 *   - Uses burst-scan approach: tap Scan button to analyze frames for 1.5 seconds.
 */
class MainActivity : AppCompatActivity() {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ManifestViewModel by viewModels()

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService

    // ML Kit (bundled models, no Play Services required)
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var barcodeScanner: BarcodeScanner

    // Adapters
    private lateinit var manifestReadyAdapter: ManifestItemAdapter
    private lateinit var missingAdapter: ManifestItemAdapter
    private lateinit var extraAdapter: ExtraItemsAdapter

    // Flag: only true during a burst scan window
    private var analysisActive = false

    // Burst scan timeout handler
    private val burstHandler = Handler(Looper.getMainLooper())
    private var burstTimeoutRunnable: Runnable? = null

    // -----------------------------------------------------------------------
    // Permission launcher
    // -----------------------------------------------------------------------

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        initMLKit()
        initAdapters()
        initClickListeners()
        observeState()
        requestCameraPermissionIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelBurstTimeout()
        cameraExecutor.shutdown()
        textRecognizer.close()
        barcodeScanner.close()
    }

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    private fun initMLKit() {
        // Bundled text recognizer (Latin script, model ships inside APK).
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Bundled barcode scanner configured for common 1-D product codes.
        val barcodeOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128
            )
            .build()
        barcodeScanner = BarcodeScanning.getClient(barcodeOptions)
    }

    private fun initAdapters() {
        manifestReadyAdapter = ManifestItemAdapter()
        missingAdapter = ManifestItemAdapter()
        extraAdapter = ExtraItemsAdapter()

        // ManifestReady list
        binding.manifestReadyScreen.rvManifestItems.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = manifestReadyAdapter
        }

        // Reporting: Missing Items tab
        binding.reportingScreen.rvMissingItems.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = missingAdapter
        }

        // Reporting: Extra Items tab
        binding.reportingScreen.rvExtraItems.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = extraAdapter
        }
    }

    private fun initClickListeners() {

        // --- Idle ---
        binding.btnCaptureManifest.setOnClickListener {
            viewModel.startCapture()
        }

        // --- Capturing ---
        binding.btnShutter.setOnClickListener {
            takePhoto()
        }

        // --- Capture Review ---
        binding.captureReviewScreen.btnReviewRetry.setOnClickListener {
            viewModel.onRetryCapture()
        }
        binding.captureReviewScreen.btnReviewProcess.setOnClickListener {
            val current = viewModel.state.value
            if (current is AppState.CaptureReview) {
                processOCR(current.croppedBitmap)
            }
        }

        // --- Manifest Ready ---
        binding.manifestReadyScreen.btnNextPage.setOnClickListener {
            viewModel.captureNextPage()
        }
        binding.manifestReadyScreen.btnStartScanning.setOnClickListener {
            viewModel.startScanning()
        }
        binding.manifestReadyScreen.btnAddItem.setOnClickListener {
            showAddItemDialog()
        }

        // --- Scan Controls ---
        binding.btnScanBarcode.setOnClickListener {
            startBurstScan()
        }
        binding.btnViewReport.setOnClickListener {
            viewModel.generateReport()
        }

        // --- Pending Confirm ---
        binding.pendingConfirmScreen.btnConfirmAccept.setOnClickListener {
            viewModel.confirmScan()
        }
        binding.pendingConfirmScreen.btnConfirmReject.setOnClickListener {
            viewModel.rejectScan()
        }

        // --- Reporting ---
        binding.reportingScreen.btnBackToScanning.setOnClickListener {
            viewModel.returnToScanning()
        }
        binding.reportingScreen.reportTabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    when (tab.position) {
                        0 -> {
                            binding.reportingScreen.missingTabContainer.visibility = View.VISIBLE
                            binding.reportingScreen.extraTabContainer.visibility = View.GONE
                        }
                        1 -> {
                            binding.reportingScreen.missingTabContainer.visibility = View.GONE
                            binding.reportingScreen.extraTabContainer.visibility = View.VISIBLE
                        }
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            }
        )

        // --- Error ---
        binding.btnTryAgain.setOnClickListener {
            viewModel.reset()
        }
    }

    // -----------------------------------------------------------------------
    // State observation
    // -----------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: AppState) {
        // Hide everything first.
        hideAllContainers()

        when (state) {
            is AppState.Idle -> showIdle()
            is AppState.Capturing -> showCapturing()
            is AppState.CaptureReview -> showCaptureReview(state)
            is AppState.Parsing -> showParsing()
            is AppState.ManifestReady -> showManifestReady(state)
            is AppState.Scanning -> showScanning(state)
            is AppState.PendingConfirm -> showPendingConfirm(state)
            is AppState.Reporting -> showReporting(state)
            is AppState.Error -> showError(state)
        }
    }

    private fun hideAllContainers() {
        analysisActive = false
        binding.previewView.visibility = View.GONE
        binding.cropOverlay.visibility = View.GONE
        binding.idleContainer.visibility = View.GONE
        binding.captureControlsContainer.visibility = View.GONE
        binding.captureReviewScreen.root.visibility = View.GONE
        binding.parsingContainer.visibility = View.GONE
        binding.manifestReadyScreen.root.visibility = View.GONE
        binding.scanControlsContainer.visibility = View.GONE
        binding.pendingConfirmScreen.root.visibility = View.GONE
        binding.reportingScreen.root.visibility = View.GONE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showIdle() {
        unbindCamera()
        binding.idleContainer.visibility = View.VISIBLE
    }

    private fun showCapturing() {
        binding.previewView.visibility = View.VISIBLE
        binding.cropOverlay.visibility = View.VISIBLE
        binding.captureControlsContainer.visibility = View.VISIBLE
        bindCaptureMode()
    }

    private fun showCaptureReview(state: AppState.CaptureReview) {
        unbindCamera()
        binding.captureReviewScreen.root.visibility = View.VISIBLE
        binding.captureReviewScreen.imgCroppedPreview.setImageBitmap(state.croppedBitmap)
    }

    private fun showParsing() {
        unbindCamera()
        binding.parsingContainer.visibility = View.VISIBLE
    }

    private fun showManifestReady(state: AppState.ManifestReady) {
        unbindCamera()
        binding.manifestReadyScreen.root.visibility = View.VISIBLE
        manifestReadyAdapter.submitList(state.items)
        binding.manifestReadyScreen.txtItemCount.text =
            getString(R.string.manifest_ready_count, state.items.size)
    }

    private fun showScanning(state: AppState.Scanning) {
        binding.previewView.visibility = View.VISIBLE
        binding.scanControlsContainer.visibility = View.VISIBLE
        analysisActive = false
        bindScanMode()

        // Update progress text.
        val totalExpected = state.items.sumOf { it.expectedCases }
        val totalScanned = state.items.sumOf { it.scannedCases }
        binding.txtScanProgress.text =
            getString(R.string.scan_progress, totalScanned, totalExpected)
    }

    private fun showPendingConfirm(state: AppState.PendingConfirm) {
        // Keep the camera preview visible (frozen) behind the overlay.
        binding.previewView.visibility = View.VISIBLE
        binding.pendingConfirmScreen.root.visibility = View.VISIBLE
        analysisActive = false
        cancelBurstTimeout()

        binding.pendingConfirmScreen.txtConfirmBarcode.text =
            getString(R.string.confirm_barcode, state.scannedBarcode)

        binding.pendingConfirmScreen.txtConfirmMatch.text =
            getString(R.string.confirm_match, state.matchedDescription)

        // Color: green for match, orange for extra.
        val matchColor = if (state.matchedIndex != null) {
            ContextCompat.getColor(this, R.color.status_complete)
        } else {
            ContextCompat.getColor(this, R.color.status_extra)
        }
        binding.pendingConfirmScreen.txtConfirmMatch.setTextColor(matchColor)

        // Show scan count for matched items (e.g., "Scan 2 of 3").
        if (state.matchedIndex != null) {
            val item = state.items[state.matchedIndex]
            val nextScan = item.scannedCases + 1
            binding.pendingConfirmScreen.txtConfirmScanCount.text =
                getString(R.string.confirm_scan_count, nextScan, item.expectedCases)
            binding.pendingConfirmScreen.txtConfirmScanCount.visibility = View.VISIBLE
        } else {
            binding.pendingConfirmScreen.txtConfirmScanCount.visibility = View.GONE
        }
    }

    private fun showReporting(state: AppState.Reporting) {
        unbindCamera()
        binding.reportingScreen.root.visibility = View.VISIBLE

        // Populate tabs if not already present.
        val tabs = binding.reportingScreen.reportTabs
        if (tabs.tabCount == 0) {
            tabs.addTab(tabs.newTab().setText(R.string.tab_missing))
            tabs.addTab(tabs.newTab().setText(R.string.tab_extra))
        }

        // Missing tab
        missingAdapter.submitList(state.missingItems)
        if (state.missingItems.isEmpty()) {
            binding.reportingScreen.rvMissingItems.visibility = View.GONE
            binding.reportingScreen.txtMissingEmpty.visibility = View.VISIBLE
        } else {
            binding.reportingScreen.rvMissingItems.visibility = View.VISIBLE
            binding.reportingScreen.txtMissingEmpty.visibility = View.GONE
        }

        // Extra tab
        extraAdapter.submitList(state.extraItems)
        if (state.extraItems.isEmpty()) {
            binding.reportingScreen.rvExtraItems.visibility = View.GONE
            binding.reportingScreen.txtExtraEmpty.visibility = View.VISIBLE
        } else {
            binding.reportingScreen.rvExtraItems.visibility = View.VISIBLE
            binding.reportingScreen.txtExtraEmpty.visibility = View.GONE
        }

        // Reset to first tab.
        tabs.selectTab(tabs.getTabAt(0))
        binding.reportingScreen.missingTabContainer.visibility = View.VISIBLE
        binding.reportingScreen.extraTabContainer.visibility = View.GONE
    }

    private fun showError(state: AppState.Error) {
        unbindCamera()
        binding.errorContainer.visibility = View.VISIBLE
        binding.txtErrorMessage.text = state.message
    }

    // -----------------------------------------------------------------------
    // Camera permission
    // -----------------------------------------------------------------------

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // -----------------------------------------------------------------------
    // CameraX setup
    // -----------------------------------------------------------------------

    private fun initCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            // Camera ready. Binding happens when state transitions to
            // Capturing or Scanning.
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Bind CameraX for Phase 1: manifest photo capture.
     * Use cases: Preview + ImageCapture at 1280x720 (portrait: 720x1280).
     */
    private fun bindCaptureMode() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        // Target resolution in portrait orientation: width=720, height=1280.
        imageCapture = ImageCapture.Builder()
            .setTargetResolution(Size(720, 1280))
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

           provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
    }

    /**
     * Bind CameraX for Phase 2: live barcode scanning.
     * Use cases: Preview + ImageAnalysis (KEEP_ONLY_LATEST backpressure).
     */
    private fun bindScanMode() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processBarcode(imageProxy)
                }
            }

        try {
            val cam = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
            // Zoom in so barcodes are readable from a comfortable distance.
            cam.cameraControl.setLinearZoom(0.3f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
    }

    // -----------------------------------------------------------------------
    // Phase 1: Photo capture, crop, and OCR
    // -----------------------------------------------------------------------

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(imageProxy)
                        val rotated = rotateBitmap(
                            bitmap,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        if (bitmap !== rotated) bitmap.recycle()

                        val cropRect = binding.cropOverlay.getCropRect()
                        val cropped = CoordinateMapper.cropBitmap(
                            sourceBitmap = rotated,
                            viewCropRect = cropRect,
                            viewWidth = binding.previewView.width,
                            viewHeight = binding.previewView.height
                        )
                        rotated.recycle()

                        viewModel.onPhotoCropped(cropped)
                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /**
     * Feed the cropped bitmap to ML Kit Text Recognition, then pass
     * the raw text into the ViewModel for parsing.
     */
    private fun processOCR(croppedBitmap: Bitmap) {
        viewModel.onProcessCrop()

        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                viewModel.onManifestTextExtracted(result.text)
            }
            .addOnFailureListener { e ->
                viewModel.onManifestTextExtracted("")  // triggers error state
            }
    }

    // -----------------------------------------------------------------------
    // Phase 2: Live barcode analysis
    // -----------------------------------------------------------------------

    /**
     * ImageAnalysis.Analyzer callback. Runs on [cameraExecutor] thread.
     * Extracts the camera image, feeds it to ML Kit Barcode, and posts
     * detected barcodes back to the ViewModel on the main thread.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processBarcode(imageProxy: ImageProxy) {
        // If analysis is paused (e.g., PendingConfirm showing), skip.
        if (!analysisActive) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        // Post to main thread.
                        runOnUiThread {
                            viewModel.onBarcodeDetected(rawValue)
                        }
                        break  // process one barcode at a time
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    // -----------------------------------------------------------------------
    // Burst scan: analyze frames for 1.5 seconds after Scan button tap
    // -----------------------------------------------------------------------

    private fun startBurstScan() {
        analysisActive = true
        cancelBurstTimeout()
        burstTimeoutRunnable = Runnable { onBurstTimeout() }
        burstHandler.postDelayed(burstTimeoutRunnable!!, 1500L)
    }

    private fun onBurstTimeout() {
        analysisActive = false
        burstTimeoutRunnable = null
        Toast.makeText(this, "No barcode detected. Try again.", Toast.LENGTH_SHORT).show()
    }

    private fun cancelBurstTimeout() {
        burstTimeoutRunnable?.let { burstHandler.removeCallbacks(it) }
        burstTimeoutRunnable = null
    }

    // -----------------------------------------------------------------------
    // Image utilities
    // -----------------------------------------------------------------------

    /**
     * Convert an ImageProxy (JPEG format from ImageCapture) to a Bitmap.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Rotate a Bitmap by the given degrees. Returns the same instance
     * if degrees is 0.
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // -----------------------------------------------------------------------
    // Manual item entry dialog
    // -----------------------------------------------------------------------

    /**
     * Shows a simple dialog for the worker to manually add a manifest line
     * when OCR misreads a row.
     */
    private fun showAddItemDialog() {
        val dialogView = layoutInflater.inflate(
            android.R.layout.simple_list_item_1, null
        )

        // Build a quick programmatic layout since we want to keep file count minimal.
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val inputUpc = TextInputEditText(this).apply {
            hint = "UPC number"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val inputDesc = TextInputEditText(this).apply {
            hint = "Item description"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val inputQty = TextInputEditText(this).apply {
            hint = "Expected cases"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        container.addView(inputUpc)
        container.addView(inputDesc)
        container.addView(inputQty)

        AlertDialog.Builder(this)
            .setTitle("Add Manifest Item")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val upc = inputUpc.text?.toString().orEmpty()
                val desc = inputDesc.text?.toString().orEmpty()
                val qty = inputQty.text?.toString()?.toIntOrNull() ?: 0
                if (upc.isNotBlank() && desc.isNotBlank() && qty > 0) {
                    viewModel.addManifestItemManually(upc, desc, qty)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
