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
import com.example.utils.AppLogger
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
        AppLogger.i(TAG, "onCreate: Ekran yakalama aktivitesi başlatılıyor.")

        // Make the activity as invisible/unobtrousive as possible
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        if (mediaProjectionManager == null) {
            AppLogger.e(TAG, "onCreate: Medya Projeksiyon yöneticisi bulunamadı!")
            Toast.makeText(this, "Ekran yakalama servisi bu cihazda desteklenmiyor.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            AppLogger.i(TAG, "onCreate: Medya projeksiyon izin diyaloğu talep ediliyor.")
            val captureIntent = mediaProjectionManager!!.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start media projection permission request picker.", e)
            Toast.makeText(this, "Ekran yakalama izni başlatılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                AppLogger.i(TAG, "onActivityResult: Ekran yakalama izni VERİLDİ. Çeviri başlatılıyor.")
                // Pre-inform user so they know details are being parsed in background
                Toast.makeText(this, "Çeviri başlatılıyor, lütfen bekleyin...", Toast.LENGTH_SHORT).show()
                startScreenCapture(resultCode, data)
            } else {
                AppLogger.w(TAG, "onActivityResult: Ekran yakalama izni kullanıcı tarafından iptal edildi veya reddedildi.")
                Toast.makeText(this, "Ekran yakalama izni iptal edildi.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            AppLogger.i(TAG, "startScreenCapture: Servisin medya projeksiyon tipine yükseltilmesi isteniyor action=START_MEDIA_PROJECTION")
            // Inform OverlayService to elevate foreground service type to media projection first
            val startFgsIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_MEDIA_PROJECTION
            }
            startService(startFgsIntent)

            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                AppLogger.e(TAG, "startScreenCapture: MediaProjection nesnesi null döndü, yakalama duruyor!")
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

            AppLogger.i(TAG, "startScreenCapture: Sanal ekran kuruluyor ($width x $height @ $density dpi)")

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

            AppLogger.i(TAG, "startScreenCapture: ImageAvailableListener dinleyicisi ekleniyor, bir kare bekleniyor...")

            // Listen for first available image frame
            imageReader!!.setOnImageAvailableListener({ reader ->
                try {
                    AppLogger.d(TAG, "setOnImageAvailableListener: Yeni bir ekran karesi algılandı!")
                    var image = reader.acquireLatestImage()
                    if (image == null) {
                        image = reader.acquireNextImage()
                    }
                    if (image != null) {
                        AppLogger.d(TAG, "setOnImageAvailableListener: Ekran karesi başarıyla okundu.")
                        val bitmap = convertImageToBitmap(image, width, height)
                        image.close()

                        if (bitmap != null) {
                            AppLogger.i(TAG, "setOnImageAvailableListener: Ekran karesi Bitmap'e dönüştürüldü (Genişlik: ${bitmap.width}, Yükseklik: ${bitmap.height})")
                            
                            // Send captured bitmap to OverlayService
                            OverlayService.setCapturedBitmap(bitmap)
                            
                            // Trigger translation process in the service
                            val serviceIntent = Intent(this@ScreenCaptureActivity, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_PROCESS_TRANSLATION
                            }
                            startService(serviceIntent)
                        } else {
                            AppLogger.e(TAG, "setOnImageAvailableListener: Ekran karesi Bitmap'e DÖNÜŞTÜRÜLEMEDİ.")
                        }

                        // Stop projection & clean up resources immediately
                        cleanupAndFinish()
                    } else {
                        AppLogger.w(TAG, "setOnImageAvailableListener: acquireLatestImage() ve acquireNextImage() null döndü.")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "setOnImageAvailableListener: Hata oluştu!", e)
                    cleanupAndFinish()
                }
            }, handler)

            // Safety timeout fallback (e.g. 4 * 1000 ms) to prevent infinite activity freeze
            handler.postDelayed({
                if (imageReader != null) {
                    val warnMsg = "Ekran karesi alınamadı! Statik optimizasyon nedeniyle olabilir. Lütfen ekranı hafifçe kaydırıp veya dokunup tekrar deneyin."
                    AppLogger.w(TAG, "startScreenCapture Fallback: 4 saniye içinde ekran karesi alınamadı, zaman aşımı tetiklendi!")
                    runOnUiThread {
                        Toast.makeText(applicationContext, warnMsg, Toast.LENGTH_LONG).show()
                    }
                    cleanupAndFinish()
                }
            }, 4005)

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed during screen capture initialization: ${e.message}", e)
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
