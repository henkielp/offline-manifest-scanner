package com.example.manifestscanner

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Converts crop rectangle coordinates from view-pixel space (the on-screen
 * CropOverlayView) into image-pixel space (the captured camera Bitmap).
 *
 * Why this is necessary:
 *   The CameraX PreviewView and the captured image have different resolutions.
 *   For example, the preview might render at 1080x1920 screen pixels while the
 *   captured image is 720x1280 (per our ImageCapture target resolution). When
 *   PreviewView uses ScaleType.FILL_CENTER, the camera feed is scaled uniformly
 *   to fill the view, with overflow cropped symmetrically on one axis. This
 *   class accounts for that scaling and offset.
 *
 * This class is deliberately stateless and has no Android framework dependencies
 * beyond Bitmap and RectF, making it straightforward to unit test.
 */
object CoordinateMapper {

    /**
     * Holds the result of a coordinate mapping operation.
     *
     * [x], [y]           Top-left corner in image pixels.
     * [width], [height]   Dimensions of the crop region in image pixels.
     *
     * All values are clamped to the image bounds and guaranteed to be non-negative.
     */
    data class ImageCropRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Maps a crop rectangle from view-pixel space to image-pixel space.
     *
     * @param viewCropRect   The crop rectangle from [CropOverlayView.getCropRect],
     *                       in the coordinate system of the PreviewView.
     * @param viewWidth      Width of the PreviewView in pixels.
     * @param viewHeight     Height of the PreviewView in pixels.
     * @param imageWidth     Width of the captured Bitmap in pixels.
     * @param imageHeight    Height of the captured Bitmap in pixels.
     *
     * @return An [ImageCropRegion] ready for use with [Bitmap.createBitmap].
     *
     * Assumptions:
     *   - CameraX PreviewView ScaleType is FILL_CENTER (the default).
     *   - Both the preview and the image share the same camera sensor aspect ratio
     *     before any view-level scaling.
     *
     * FILL_CENTER math:
     *   The preview is scaled uniformly so the shorter dimension fits exactly,
     *   and the longer dimension overflows (is cropped symmetrically).
     *
     *   scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
     *
     *   The "virtual" size of the image when projected into view space:
     *     virtualW = imageWidth  * scale
     *     virtualH = imageHeight * scale
     *
     *   Offset (how much of the image overflows off-screen on each side):
     *     offsetX = (virtualW - viewWidth)  / 2
     *     offsetY = (virtualH - viewHeight) / 2
     *
     *   Converting a view-space coordinate to image-space:
     *     imageX = (viewX + offsetX) / scale
     */
    fun mapViewRectToImageRect(
        viewCropRect: RectF,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): ImageCropRegion {

        require(viewWidth > 0 && viewHeight > 0) {
            "View dimensions must be positive: ${viewWidth}x${viewHeight}"
        }
        require(imageWidth > 0 && imageHeight > 0) {
            "Image dimensions must be positive: ${imageWidth}x${imageHeight}"
        }

        // Uniform scale factor used by FILL_CENTER.
        val scale = max(
            viewWidth.toFloat() / imageWidth.toFloat(),
            viewHeight.toFloat() / imageHeight.toFloat()
        )

        // Virtual projected size of the full image in view-pixel space.
        val virtualW = imageWidth * scale
        val virtualH = imageHeight * scale

        // Symmetric overflow offset.
        val offsetX = (virtualW - viewWidth) / 2f
        val offsetY = (virtualH - viewHeight) / 2f

        // Map each edge from view space to image space.
        val imgLeft   = (viewCropRect.left   + offsetX) / scale
        val imgTop    = (viewCropRect.top    + offsetY) / scale
        val imgRight  = (viewCropRect.right  + offsetX) / scale
        val imgBottom = (viewCropRect.bottom + offsetY) / scale

        // Clamp to image bounds.
        val clampedLeft   = imgLeft.coerceIn(0f, imageWidth.toFloat())
        val clampedTop    = imgTop.coerceIn(0f, imageHeight.toFloat())
        val clampedRight  = imgRight.coerceIn(0f, imageWidth.toFloat())
        val clampedBottom = imgBottom.coerceIn(0f, imageHeight.toFloat())

        val x = clampedLeft.roundToInt()
        val y = clampedTop.roundToInt()
        val w = (clampedRight - clampedLeft).roundToInt().coerceAtLeast(1)
        val h = (clampedBottom - clampedTop).roundToInt().coerceAtLeast(1)

        // Final safety clamp: make sure x+w and y+h do not exceed image bounds.
        val safeW = w.coerceAtMost(imageWidth - x)
        val safeH = h.coerceAtMost(imageHeight - y)

        return ImageCropRegion(
            x = x,
            y = y,
            width = safeW,
            height = safeH
        )
    }

    /**
     * Convenience method: crops the source Bitmap using the mapped coordinates.
     *
     * @return A new Bitmap containing only the crop region. The caller is
     *         responsible for recycling [sourceBitmap] if it is no longer needed.
     */
    fun cropBitmap(
        sourceBitmap: Bitmap,
        viewCropRect: RectF,
        viewWidth: Int,
        viewHeight: Int
    ): Bitmap {
        val region = mapViewRectToImageRect(
            viewCropRect = viewCropRect,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            imageWidth = sourceBitmap.width,
            imageHeight = sourceBitmap.height
        )

        return Bitmap.createBitmap(
            sourceBitmap,
            region.x,
            region.y,
            region.width,
            region.height
        )
    }
}
