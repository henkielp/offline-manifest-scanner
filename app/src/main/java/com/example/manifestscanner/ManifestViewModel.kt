package com.example.manifestscanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------------------
// Data Classes
// ---------------------------------------------------------------------------

/**
 * Represents one line item from a printed delivery manifest.
 *
 * [upc]            The UPC as printed on paper (often 10 or 11 digits, leading zeros dropped).
 * [description]    Human-readable item name extracted via OCR.
 * [expectedCases]  Quantity listed on the manifest.
 * [scannedCases]   Running count of confirmed physical scans. Starts at 0.
 */
data class ManifestItem(
    val upc: String,
    val description: String,
    val expectedCases: Int,
    val scannedCases: Int = 0
)

/**
 * Tracks barcodes that were physically scanned but do not match any manifest line.
 */
data class ExtraItem(
    val barcode: String,
    val scanCount: Int = 1
)

// ---------------------------------------------------------------------------
// State Machine
// ---------------------------------------------------------------------------

/**
 * Finite set of application states. Every screen in the app maps to exactly one
 * of these sealed variants. The ViewModel is the single source of truth.
 *
 * State graph:
 *
 *   Idle
 *     |  (user taps "Capture Manifest")
 *     v
 *   Capturing           <-- camera preview with CropOverlayView
 *     |  (shutter tap, bitmap cropped)
 *     v
 *   CaptureReview       <-- shows cropped bitmap, Retry / Process buttons
 *     |  Retry --> back to Capturing
 *     |  Process --> Parsing
 *     v
 *   Parsing
 *     |  (OCR complete)
 *     v
 *   ManifestReady
 *     |  (start scanning)
 *     v
 *   Scanning            <-- live barcode analysis
 *     |  (barcode detected)
 *     v
 *   PendingConfirm      <-- camera frozen, awaiting user confirm/reject
 *     |  confirm --> Scanning (scannedCases incremented)
 *     |  reject  --> Scanning (no change)
 *     |
 *     |  (user taps "Report")
 *     v
 *   Reporting           <-- two-tab discrepancy view
 *     |  (back)
 *     v
 *   Scanning
 */
sealed interface AppState {

    /** Launch state. No manifest has been loaded yet. */
    data object Idle : AppState

    /** Camera preview is active with the CropOverlayView displayed. */
    data object Capturing : AppState

    /**
     * The photo has been taken and cropped. The UI shows the cropped image
     * so the worker can verify the correct columns were captured.
     *
     * [croppedBitmap] The bitmap after crop-guide coordinates were applied.
     *                 Held in memory; recycled on Retry or after OCR completes.
     */
    data class CaptureReview(
        val croppedBitmap: Bitmap
    ) : AppState

    /** OCR parsing is in progress. */
    data object Parsing : AppState

    /** Manifest successfully parsed. Items are populated and ready for scanning. */
    data class ManifestReady(
        val items: List<ManifestItem>
    ) : AppState

    /** Live barcode analysis is running. Camera preview is active. */
    data class Scanning(
        val items: List<ManifestItem>,
        val extraItems: List<ExtraItem>
    ) : AppState

    /**
     * A barcode was detected and optionally matched to a manifest line.
     * Camera is frozen. The user must confirm via screen tap or Volume Up.
     *
     * [matchedIndex] is null when the barcode has no manifest match (extra item).
     */
    data class PendingConfirm(
        val items: List<ManifestItem>,
        val extraItems: List<ExtraItem>,
        val scannedBarcode: String,
        val matchedIndex: Int?,
        val matchedDescription: String
    ) : AppState

    /** Two-tab discrepancy report: Missing Items and Extra Items. */
    data class Reporting(
        val missingItems: List<ManifestItem>,
        val fullyReceivedItems: List<ManifestItem>,
        val extraItems: List<ExtraItem>
    ) : AppState

    /** An unrecoverable error with a user-facing message. */
    data class Error(val message: String) : AppState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class ManifestViewModel : ViewModel() {

    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state.asStateFlow()

    // Internal mutable copies kept across state transitions.
    private var manifestItems: MutableList<ManifestItem> = mutableListOf()
    private var extraItems: MutableList<ExtraItem> = mutableListOf()

    // When true, the next OCR result appends to the existing list
    // instead of replacing it. Set by captureNextPage().
    private var appendMode = false

    // -----------------------------------------------------------------------
    // Phase 1a: Capture flow (camera with crop overlay)
    // -----------------------------------------------------------------------

     /** User taps "Capture Manifest" from the Idle screen. */
    fun startCapture() {
        appendMode = false
        _state.value = AppState.Capturing
    }

    /** User taps "Scan Next Page" from ManifestReady. Keeps existing items. */
    fun captureNextPage() {
        appendMode = true
        _state.value = AppState.Capturing
    }

    /**
     * Called after CameraX ImageCapture fires and the bitmap has been cropped
     * to the CropOverlayView rectangle via [CoordinateMapper.cropBitmap].
     *
     * Transitions to CaptureReview so the worker can verify the crop.
     */
    fun onPhotoCropped(croppedBitmap: Bitmap) {
        _state.value = AppState.CaptureReview(croppedBitmap = croppedBitmap)
    }

    /**
     * Worker rejected the crop preview and wants to retake the photo.
     * Recycles the bitmap held in CaptureReview to free memory.
     */
    fun onRetryCapture() {
        val current = _state.value
        if (current is AppState.CaptureReview && !current.croppedBitmap.isRecycled) {
            current.croppedBitmap.recycle()
        }
        _state.value = AppState.Capturing
    }

    // -----------------------------------------------------------------------
    // Phase 1b: Ingestion (OCR text to structured manifest)
    // -----------------------------------------------------------------------

    /**
     * Worker approved the crop preview and tapped "Process".
     *
     * The caller should:
     *   1. Extract the croppedBitmap from the CaptureReview state.
     *   2. Feed it to ML Kit Text Recognition.
     *   3. Call [onManifestTextExtracted] with the raw OCR text result.
     *
     * This method transitions to Parsing to show a loading indicator.
     */
    fun onProcessCrop() {
        _state.value = AppState.Parsing
    }

    /**
     * Accepts the raw text block returned by ML Kit Text Recognition and
     * attempts to parse it into a list of [ManifestItem] entries.
     *
     * Expected manifest format (one item per line):
     *   <UPC>   <Description>   <Qty>
     *
     * The parser is intentionally tolerant of OCR noise:
     *   - Lines with fewer than 3 tokens are skipped.
     *   - The UPC token must be purely numeric (after stripping whitespace).
     *   - The quantity token must be a positive integer.
     *   - Everything between UPC and quantity is treated as the description.
     */
    fun onManifestTextExtracted(rawText: String) {
        if (_state.value !is AppState.Parsing) {
            _state.value = AppState.Parsing
        }

        val parsed = parseManifestText(rawText)

if (parsed.isEmpty()) {
            _state.value = AppState.Error(
                "Could not parse any items from the captured image. " +
                "Make sure the UPC, Description, and Cases columns are " +
                "fully inside the crop guide and well-lit."
            )
            return
        }

        if (appendMode) {
            manifestItems.addAll(parsed)
        } else {
            manifestItems = parsed.toMutableList()
            extraItems.clear()
        }
        appendMode = false
        _state.value = AppState.ManifestReady(items = manifestItems.toList())
    }

    /**
     * Allows the user to manually add or correct a manifest line
     * (useful when OCR misreads a row).
     */
    fun addManifestItemManually(upc: String, description: String, expectedCases: Int) {
        val item = ManifestItem(
            upc = upc.trim(),
            description = description.trim(),
            expectedCases = expectedCases
        )
        manifestItems.add(item)
        _state.value = AppState.ManifestReady(items = manifestItems.toList())
    }

    // -----------------------------------------------------------------------
    // Phase 2: Scanning & Confirmation
    // -----------------------------------------------------------------------

    /** Transition from ManifestReady into live Scanning mode. */
    fun startScanning() {
        _state.value = AppState.Scanning(
            items = manifestItems.toList(),
            extraItems = extraItems.toList()
        )
    }

    /**
     * Called by the CameraX ImageAnalysis callback each time ML Kit Barcode
     * decodes a value. This is the core matching entry point.
     *
     * CRITICAL MATCHING RULE (substring):
     * Printed manifests frequently drop leading zeros, producing 10 or 11-digit
     * UPCs. Physical barcodes scan as full 12 or 13-digit strings. A match is
     * valid when the shorter printed UPC appears as a contiguous substring
     * anywhere inside the longer scanned barcode string.
     *
     * On match: transition to PendingConfirm so the user can verify.
     * On no match: still transition to PendingConfirm, flagged as an extra item.
     */
    fun onBarcodeDetected(scannedBarcode: String) {
        // Ignore duplicate rapid-fire detections while already pending.
        if (_state.value is AppState.PendingConfirm) return

        val cleanBarcode = scannedBarcode.trim()
        if (cleanBarcode.isEmpty()) return

        val matchResult = findMatchingItem(cleanBarcode, manifestItems)

        val description = if (matchResult != null) {
            manifestItems[matchResult].description
        } else {
            "NOT ON MANIFEST"
        }

        _state.value = AppState.PendingConfirm(
            items = manifestItems.toList(),
            extraItems = extraItems.toList(),
            scannedBarcode = cleanBarcode,
            matchedIndex = matchResult,
            matchedDescription = description
        )
    }

    /**
     * User confirmed the scan (screen tap or Volume Up hardware key).
     *
     * CRITICAL QUANTITY RULE:
     * Each confirmation increments [scannedCases] by exactly 1. If a manifest
     * line expects 2 cases, the worker must physically scan and confirm twice.
     */
    fun confirmScan() {
        val pending = _state.value as? AppState.PendingConfirm ?: return

        if (pending.matchedIndex != null) {
            // Matched a manifest line: increment its scannedCases by 1.
            val idx = pending.matchedIndex
            val current = manifestItems[idx]
            manifestItems[idx] = current.copy(scannedCases = current.scannedCases + 1)
        } else {
            // Extra item: not on the manifest.
            val existing = extraItems.indexOfFirst { it.barcode == pending.scannedBarcode }
            if (existing >= 0) {
                val current = extraItems[existing]
                extraItems[existing] = current.copy(scanCount = current.scanCount + 1)
            } else {
                extraItems.add(ExtraItem(barcode = pending.scannedBarcode))
            }
        }

        // Return to live scanning.
        _state.value = AppState.Scanning(
            items = manifestItems.toList(),
            extraItems = extraItems.toList()
        )
    }

    /** User rejected the scan (e.g., accidental read). Return to scanning. */
    fun rejectScan() {
        _state.value = AppState.Scanning(
            items = manifestItems.toList(),
            extraItems = extraItems.toList()
        )
    }

    // -----------------------------------------------------------------------
    // Phase 3: Discrepancy Report
    // -----------------------------------------------------------------------

    /**
     * Generate the two-tab report.
     *   Tab 1, "Missing Items": manifest lines where scannedCases < expectedCases.
     *   Tab 2, "Extra Items":   barcodes scanned that matched no manifest line.
     */
    fun generateReport() {
        val missing = manifestItems.filter { it.scannedCases < it.expectedCases }
        val fullyReceived = manifestItems.filter { it.scannedCases >= it.expectedCases }

        _state.value = AppState.Reporting(
            missingItems = missing,
            fullyReceivedItems = fullyReceived,
            extraItems = extraItems.toList()
        )
    }

    // -----------------------------------------------------------------------
    // Navigation helpers
    // -----------------------------------------------------------------------

    /** Return to scanning from the report screen. */
    fun returnToScanning() {
        _state.value = AppState.Scanning(
            items = manifestItems.toList(),
            extraItems = extraItems.toList()
        )
    }

    /** Full reset back to Idle. Recycles any held bitmaps. */
    fun reset() {
        val current = _state.value
        if (current is AppState.CaptureReview && !current.croppedBitmap.isRecycled) {
            current.croppedBitmap.recycle()
        }
        manifestItems.clear()
        extraItems.clear()
        _state.value = AppState.Idle
    }

    // -----------------------------------------------------------------------
    // Progress queries
    // -----------------------------------------------------------------------

    /** Fraction of expected total cases that have been scanned so far. */
    fun overallProgress(): Float {
        val totalExpected = manifestItems.sumOf { it.expectedCases }
        if (totalExpected == 0) return 0f
        val totalScanned = manifestItems.sumOf { it.scannedCases }
        return totalScanned.toFloat() / totalExpected.toFloat()
    }

    /** Count of manifest lines still missing at least one case. */
    fun outstandingLineCount(): Int =
        manifestItems.count { it.scannedCases < it.expectedCases }

    // -----------------------------------------------------------------------
    // Internal: Substring Matching Engine
    // -----------------------------------------------------------------------

    /**
     * Searches [items] for the first entry whose printed UPC appears as a
     * contiguous substring inside [scannedBarcode].
     *
     * Returns the index into [items], or null if no match is found.
     *
     * Why substring and not equality?
     * Printed manifests commonly truncate leading zeros. A manifest may print
     * "7874222239" (10 digits) while the physical barcode encodes
     * "07874222239" or "007874222239" (12/13 digits). Substring containment
     * handles every truncation pattern without requiring knowledge of the
     * specific barcode symbology (UPC-A, EAN-13, etc.).
     *
     * Tie-breaking: first match wins. In practice, UPC codes within a single
     * delivery manifest are distinct enough that collisions do not occur.
     */
    internal fun findMatchingItem(
        scannedBarcode: String,
        items: List<ManifestItem>
    ): Int? {
        for ((index, item) in items.withIndex()) {
            if (item.upc.isNotEmpty() && scannedBarcode.contains(item.upc)) {
                return index
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Internal: OCR Text Parser
    // -----------------------------------------------------------------------

    /**
     * Parses raw OCR output into structured [ManifestItem] entries.
     *
     * Strategy:
     * 1. Split into lines and discard blanks.
     * 2. For each line, tokenize on two-or-more whitespace characters
     *    (OCR tends to preserve column gaps as wide spaces).
     * 3. Identify the UPC token (longest purely numeric token) and the
     *    quantity token (short numeric token, typically 1 to 3 digits).
     * 4. Everything else on the line becomes the description.
     *
     * Lines that do not contain both a UPC candidate and a quantity candidate
     * are silently skipped (they are likely header rows or OCR artifacts).
     */
    internal fun parseManifestText(rawText: String): List<ManifestItem> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        val upcs = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val quantities = mutableListOf<Int>()

        var phase = 0

        for (line in lines) {
            val stripped = line.replace("\\s".toRegex(), "")
            val isPureDigits = stripped.all { it.isDigit() } && stripped.isNotEmpty()

            when (phase) {
                0 -> {
                    if (isPureDigits) {
                        when {
                            stripped.length >= 10 -> upcs.add(stripped)
                            stripped.length == 9 -> { }
                            else -> { }
                        }
                    } else {
                        phase = 1
                        descriptions.add(line)
                    }
                }

                1 -> {
                    if (isPureDigits && stripped.length in 1..4 && descriptions.isNotEmpty()) {
                        val qty = stripped.toIntOrNull()
                        if (qty != null && qty > 0) {
                            phase = 2
                            quantities.add(qty)
                        } else {
                            descriptions.add(line)
                        }
                    } else {
                        descriptions.add(line)
                    }
                }

                2 -> {
                    if (isPureDigits && stripped.length in 1..4) {
                        val qty = stripped.toIntOrNull()
                        if (qty != null && qty > 0) {
                            quantities.add(qty)
                        }
                    }
                }
            }
        }

if (upcs.isEmpty()) return emptyList()

        val itemCount = upcs.size
        val mergedDescriptions = mergeDescriptions(descriptions, itemCount)

        val finalQuantities = MutableList(itemCount) { i ->
            quantities.getOrElse(i) { 1 }
        }

       return upcs.mapIndexed { i, upc ->
            ManifestItem(
                upc = upc,
                description = mergedDescriptions.getOrElse(i) { "Item ${i + 1}" },
                expectedCases = finalQuantities.getOrElse(i) { 1 }
            )
        }
    }

    private fun mergeDescriptions(
        descriptions: List<String>,
        targetCount: Int
    ): List<String> {
        if (descriptions.isEmpty()) {
            return List(targetCount) { "Item ${it + 1}" }
        }
        if (descriptions.size == targetCount) {
            return descriptions
        }
        if (descriptions.size < targetCount) {
            return descriptions + List(targetCount - descriptions.size) {
                "Item ${descriptions.size + it + 1}"
            }
        }

        val result = mutableListOf<String>()
        val linesPerItem = descriptions.size.toFloat() / targetCount.toFloat()

        var cursor = 0f
        for (i in 0 until targetCount) {
            val nextCursor = cursor + linesPerItem
            val startIdx = cursor.toInt()
            val endIdx = nextCursor.toInt().coerceAtLeast(startIdx + 1)
                .coerceAtMost(descriptions.size)
            val merged = descriptions.subList(startIdx, endIdx).joinToString(" ")
            result.add(merged)
            cursor = nextCursor
        }

        return result
    }
}
