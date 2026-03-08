package com.example.manifestscanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A transparent overlay drawn on top of the CameraX PreviewView.
 *
 * Responsibilities:
 *   1. Draw a semi-transparent dark scrim over the entire camera preview.
 *   2. Punch a clear rectangular "crop guide" hole through the scrim.
 *   3. Draw 8 drag handles (4 corners + 4 edge midpoints).
 *   4. Process touch events to let the user resize the rectangle.
 *
 * The rectangle coordinates (in view-pixel space) are exposed via [getCropRect]
 * so the capture pipeline can map them onto the actual camera image.
 *
 * Design choices for bakery/deli environment:
 *   - 48dp touch targets so workers wearing insulated gloves can grab handles.
 *   - Minimum rectangle size of 100dp to prevent accidental collapse.
 *   - High-contrast white handles against the dark scrim.
 *   - Default size: 80% of view width, 70% of view height, centered.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // -----------------------------------------------------------------------
    // Configuration constants
    // -----------------------------------------------------------------------

    /** Touch hit-test radius in density-independent pixels. */
    private val TOUCH_TARGET_DP = 48f

    /** Minimum crop rectangle dimension in density-independent pixels. */
    private val MIN_SIZE_DP = 100f

    /** Corner handle: drawn as a square with this side length in dp. */
    private val CORNER_HANDLE_DP = 16f

    /** Edge handle: drawn as a rectangle with this length on the long axis in dp. */
    private val EDGE_HANDLE_LENGTH_DP = 24f

    /** Edge handle: thickness on the short axis in dp. */
    private val EDGE_HANDLE_THICKNESS_DP = 8f

    /** Crop guide border stroke width in dp. */
    private val BORDER_STROKE_DP = 2f

    /** Fraction of view width for the default crop rectangle. */
    private val DEFAULT_WIDTH_FRACTION = 0.80f

    /** Fraction of view height for the default crop rectangle. */
    private val DEFAULT_HEIGHT_FRACTION = 0.70f

    // -----------------------------------------------------------------------
    // Derived pixel values (computed once in onSizeChanged)
    // -----------------------------------------------------------------------

    private val density = resources.displayMetrics.density

    private val touchTargetPx = TOUCH_TARGET_DP * density
    private val minSizePx = MIN_SIZE_DP * density
    private val cornerHandlePx = CORNER_HANDLE_DP * density
    private val edgeHandleLengthPx = EDGE_HANDLE_LENGTH_DP * density
    private val edgeHandleThicknessPx = EDGE_HANDLE_THICKNESS_DP * density
    private val borderStrokePx = BORDER_STROKE_DP * density

    // -----------------------------------------------------------------------
    // The crop rectangle (in view-pixel coordinates)
    // -----------------------------------------------------------------------

    private val cropRect = RectF()
    private var initialized = false

    // -----------------------------------------------------------------------
    // Paints
    // -----------------------------------------------------------------------

    /** Semi-transparent dark overlay covering the entire view. */
    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** Used to punch the clear hole through the scrim. */
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    /** White border around the crop rectangle. */
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = borderStrokePx
        isAntiAlias = true
    }

    /** Solid white fill for the drag handles. */
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // -----------------------------------------------------------------------
    // Drag state
    // -----------------------------------------------------------------------

    /**
     * Identifies which of the 8 drag zones the user grabbed, or NONE.
     */
    private enum class DragHandle {
        NONE,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP_EDGE, BOTTOM_EDGE, LEFT_EDGE, RIGHT_EDGE
    }

    private var activeHandle = DragHandle.NONE

    /** Touch coordinate at the previous ACTION_MOVE event. */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            initDefaultRect(w, h)
            initialized = true
        }
    }

    private fun initDefaultRect(viewWidth: Int, viewHeight: Int) {
        val rectW = viewWidth * DEFAULT_WIDTH_FRACTION
        val rectH = viewHeight * DEFAULT_HEIGHT_FRACTION
        val left = (viewWidth - rectW) / 2f
        val top = (viewHeight - rectH) / 2f
        cropRect.set(left, top, left + rectW, top + rectH)
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // We need an offscreen layer so PorterDuff.CLEAR works correctly.
        val saveCount = canvas.saveLayer(
            0f, 0f, width.toFloat(), height.toFloat(), null
        )

        // 1. Fill entire canvas with semi-transparent scrim.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. Punch a clear hole for the crop rectangle.
        canvas.drawRect(cropRect, clearPaint)

        // Restore the layer so subsequent draws are normal compositing.
        canvas.restoreToCount(saveCount)

        // 3. White border around the crop guide.
        canvas.drawRect(cropRect, borderPaint)

        // 4. Draw 8 handles.
        drawCornerHandles(canvas)
        drawEdgeHandles(canvas)
    }

    private fun drawCornerHandles(canvas: Canvas) {
        val half = cornerHandlePx / 2f
        val corners = arrayOf(
            cropRect.left to cropRect.top,
            cropRect.right to cropRect.top,
            cropRect.left to cropRect.bottom,
            cropRect.right to cropRect.bottom
        )
        for ((cx, cy) in corners) {
            canvas.drawRect(
                cx - half, cy - half,
                cx + half, cy + half,
                handlePaint
            )
        }
    }

    private fun drawEdgeHandles(canvas: Canvas) {
        val midX = (cropRect.left + cropRect.right) / 2f
        val midY = (cropRect.top + cropRect.bottom) / 2f
        val halfLen = edgeHandleLengthPx / 2f
        val halfThick = edgeHandleThicknessPx / 2f

        // Top edge (horizontal bar)
        canvas.drawRect(
            midX - halfLen, cropRect.top - halfThick,
            midX + halfLen, cropRect.top + halfThick,
            handlePaint
        )
        // Bottom edge (horizontal bar)
        canvas.drawRect(
            midX - halfLen, cropRect.bottom - halfThick,
            midX + halfLen, cropRect.bottom + halfThick,
            handlePaint
        )
        // Left edge (vertical bar)
        canvas.drawRect(
            cropRect.left - halfThick, midY - halfLen,
            cropRect.left + halfThick, midY + halfLen,
            handlePaint
        )
        // Right edge (vertical bar)
        canvas.drawRect(
            cropRect.right - halfThick, midY - halfLen,
            cropRect.right + halfThick, midY + halfLen,
            handlePaint
        )
    }

    // -----------------------------------------------------------------------
    // Touch handling
    // -----------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitTest(event.x, event.y)
                if (activeHandle != DragHandle.NONE) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == DragHandle.NONE) return false

                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                applyDrag(activeHandle, dx, dy)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = DragHandle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Determine which drag handle (if any) is within touch range of (x, y).
     * Corners are tested first so they take priority over overlapping edges.
     */
    private fun hitTest(x: Float, y: Float): DragHandle {
        val midX = (cropRect.left + cropRect.right) / 2f
        val midY = (cropRect.top + cropRect.bottom) / 2f
        val t = touchTargetPx

        // Corners (priority)
        if (dist(x, y, cropRect.left, cropRect.top) < t) return DragHandle.TOP_LEFT
        if (dist(x, y, cropRect.right, cropRect.top) < t) return DragHandle.TOP_RIGHT
        if (dist(x, y, cropRect.left, cropRect.bottom) < t) return DragHandle.BOTTOM_LEFT
        if (dist(x, y, cropRect.right, cropRect.bottom) < t) return DragHandle.BOTTOM_RIGHT

        // Edges
        if (abs(y - cropRect.top) < t && x > cropRect.left && x < cropRect.right) {
            return DragHandle.TOP_EDGE
        }
        if (abs(y - cropRect.bottom) < t && x > cropRect.left && x < cropRect.right) {
            return DragHandle.BOTTOM_EDGE
        }
        if (abs(x - cropRect.left) < t && y > cropRect.top && y < cropRect.bottom) {
            return DragHandle.LEFT_EDGE
        }
        if (abs(x - cropRect.right) < t && y > cropRect.top && y < cropRect.bottom) {
            return DragHandle.RIGHT_EDGE
        }

        return DragHandle.NONE
    }

    /**
     * Move the appropriate edges of [cropRect] based on which handle is active.
     * Enforces minimum size and clamps to view bounds.
     */
    private fun applyDrag(handle: DragHandle, dx: Float, dy: Float) {
        when (handle) {
            DragHandle.TOP_LEFT -> {
                cropRect.left = clampLeft(cropRect.left + dx)
                cropRect.top = clampTop(cropRect.top + dy)
            }
            DragHandle.TOP_RIGHT -> {
                cropRect.right = clampRight(cropRect.right + dx)
                cropRect.top = clampTop(cropRect.top + dy)
            }
            DragHandle.BOTTOM_LEFT -> {
                cropRect.left = clampLeft(cropRect.left + dx)
                cropRect.bottom = clampBottom(cropRect.bottom + dy)
            }
            DragHandle.BOTTOM_RIGHT -> {
                cropRect.right = clampRight(cropRect.right + dx)
                cropRect.bottom = clampBottom(cropRect.bottom + dy)
            }
            DragHandle.TOP_EDGE -> {
                cropRect.top = clampTop(cropRect.top + dy)
            }
            DragHandle.BOTTOM_EDGE -> {
                cropRect.bottom = clampBottom(cropRect.bottom + dy)
            }
            DragHandle.LEFT_EDGE -> {
                cropRect.left = clampLeft(cropRect.left + dx)
            }
            DragHandle.RIGHT_EDGE -> {
                cropRect.right = clampRight(cropRect.right + dx)
            }
            DragHandle.NONE -> { /* no-op */ }
        }
    }

    // Clamping helpers: keep edges within view bounds and enforce minimum size.

    private fun clampLeft(proposed: Float): Float {
        val minBound = 0f
        val maxBound = cropRect.right - minSizePx
        return proposed.coerceIn(minBound, maxBound)
    }

    private fun clampRight(proposed: Float): Float {
        val minBound = cropRect.left + minSizePx
        val maxBound = width.toFloat()
        return proposed.coerceIn(minBound, maxBound)
    }

    private fun clampTop(proposed: Float): Float {
        val minBound = 0f
        val maxBound = cropRect.bottom - minSizePx
        return proposed.coerceIn(minBound, maxBound)
    }

    private fun clampBottom(proposed: Float): Float {
        val minBound = cropRect.top + minSizePx
        val maxBound = height.toFloat()
        return proposed.coerceIn(minBound, maxBound)
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a copy of the current crop rectangle in view-pixel coordinates.
     * The caller (capture pipeline) uses [CoordinateMapper] to convert these
     * into the actual image-pixel coordinates for bitmap cropping.
     */
    fun getCropRect(): RectF = RectF(cropRect)

    /**
     * Programmatically reset the crop guide to its default centered position.
     */
    fun resetToDefault() {
        if (width > 0 && height > 0) {
            initDefaultRect(width, height)
            invalidate()
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /** Euclidean distance between two points. */
    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
