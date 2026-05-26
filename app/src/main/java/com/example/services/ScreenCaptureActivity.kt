package com.example.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import java.nio.ByteBuffer

class ScreenCaptureActivity : Activity() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 3003
        private const val TAG = "ScreenCaptureActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make the activity as invisible/unobtrousive as possible
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        if (mediaProjectionManager == null) {
            Toast.makeText(this, "Ekran yakalama servisi bu cihazda desteklenmiyor.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            val captureIntent = mediaProjectionManager!!.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start media projection permission request picker.", e)
            Toast.makeText(this, "Ekran yakalama izni başlatılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // Pre-inform user so they know details are being parsed in background
                Toast.makeText(this, "Çeviri başlatılıyor, lütfen bekleyin...", Toast.LENGTH_SHORT).show()
                startScreenCapture(resultCode, data)
            } else {
                Toast.makeText(this, "Ekran yakalama izni iptal edildi.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            // Inform OverlayService to elevate foreground service type to media projection first
            val startFgsIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_MEDIA_PROJECTION
            }
            startService(startFgsIntent)

            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Toast.makeText(this, "Medya projeksiyonu başlatılamadı.", Toast.LENGTH_SHORT).show()
                cleanupAndFinish()
                return
            }

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // Setup image reader to pull current frame
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            // Build the virtual display to grab stream into imageReader's surface
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "MangaCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            // Listen for first available image frame
            imageReader!!.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = convertImageToBitmap(image, width, height)
                        image.close()

                        if (bitmap != null) {
                            Log.d(TAG, "Screen captured successfully! Width: ${bitmap.width}, Height: ${bitmap.height}")
                            
                            // Send captured bitmap to OverlayService
                            OverlayService.setCapturedBitmap(bitmap)
                            
                            // Trigger translation process in the service
                            val serviceIntent = Intent(this@ScreenCaptureActivity, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_PROCESS_TRANSLATION
                            }
                            startService(serviceIntent)
                        } else {
                            Log.e(TAG, "Converted screen capture bitmap turned out null.")
                        }

                        // Stop projection & clean up resources immediately
                        cleanupAndFinish()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring image from reader: ${e.message}", e)
                    cleanupAndFinish()
                }
            }, handler)

            // Safety timeout fallback (e.g. 4 seconds) to prevent infinite activity freeze
            handler.postDelayed({
                if (imageReader != null) {
                    cleanupAndFinish()
                }
            }, 4005)

        } catch (e: Exception) {
            Log.e(TAG, "Failed during screen capture initialization: ${e.message}", e)
            cleanupAndFinish()
        }
    }

    private fun convertImageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            // Build a temporary bitmap accommodating row padding
            val tmpBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            tmpBitmap.copyPixelsFromBuffer(buffer)

            // Crop the bitmap to get the precise required screen dimensions
            bitmap = Bitmap.createBitmap(tmpBitmap, 0, 0, width, height)
            if (tmpBitmap != bitmap) {
                tmpBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image frame plane to Bitmap: ${e.message}", e)
        }
        return bitmap
    }

    private fun cleanupAndFinish() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up screen capture components: ${e.message}")
        }
        
        try {
            // Signal OverlayService to demote its foreground service type back to normal
            val stopFgsIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP_MEDIA_PROJECTION
            }
            startService(stopFgsIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed signaling demote FGS type: ${e.message}")
        }
        
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cleanupAndFinish()
        super.onDestroy()
    }
}
