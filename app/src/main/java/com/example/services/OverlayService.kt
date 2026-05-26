package com.example.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.api.SourceLanguage
import com.example.api.TargetLanguage
import com.example.utils.TranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var isFloatingWidgetVisible = false
    
    private var loadingOverlay: View? = null
    private var translationResultOverlay: View? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val CHANNEL_ID = "MangaTranslatorChannel"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_SHOW_WIDGET = "com.example.action.SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "com.example.action.HIDE_WIDGET"
        const val ACTION_CAPTURE_SCREEN = "com.example.action.CAPTURE_SCREEN"
        const val ACTION_PROCESS_TRANSLATION = "com.example.action.PROCESS_TRANSLATION"

        @Volatile
        private var capturedScreenBitmap: Bitmap? = null

        fun setCapturedBitmap(bitmap: Bitmap) {
            synchronized(this) {
                val old = capturedScreenBitmap
                capturedScreenBitmap = bitmap
                if (old != null && old != bitmap && !old.isRecycled) {
                    try {
                        old.recycle()
                    } catch (e: Exception) {
                        Log.e("OverlayService", "Failed to recycle old screen capture bitmap", e)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Manga Çevirmeni Arka Planda Çalışıyor"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Manga Çevirmeni Arka Planda Çalışıyor"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Manga Çevirmeni Arka Planda Çalışıyor"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_WIDGET -> {
                if (!isFloatingWidgetVisible) {
                    showFloatingWidget()
                }
            }
            ACTION_HIDE_WIDGET -> {
                hideFloatingWidget()
                stopSelf()
            }
            ACTION_PROCESS_TRANSLATION -> {
                val bitmapToTranslate = capturedScreenBitmap
                if (bitmapToTranslate != null) {
                    processScreenTranslation(bitmapToTranslate)
                } else {
                    Toast.makeText(this, "Yakalama hatası! Görsel yüklenemedi.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        try {
            floatingView = FrameLayout(this).apply {
                val sizePx = (56 * resources.displayMetrics.density).toInt()
                
                val imageView = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_search)
                    setBackgroundColor(0xFF232D3F.toInt()) 
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    clipToOutline = true
                    elevation = 10f
                    
                    val shape = android.graphics.drawable.GradientDrawable()
                    shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                    shape.setColor(0xFF00ADB5.toInt()) 
                    shape.setStroke(4, 0xFFEEEEEE.toInt())
                    background = shape
                }
                
                addView(imageView, FrameLayout.LayoutParams(sizePx, sizePx))
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            floatingView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = (event.rawX - initialTouchX).toInt()
                            val deltaY = (event.rawY - initialTouchY).toInt()

                            // Use dynamic 300 scale to distinguish dragging vs click with high accuracy
                            if (deltaX * deltaX + deltaY * deltaY > 300) {
                                isDragging = true
                            }

                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            try {
                                windowManager.updateViewLayout(floatingView, params)
                            } catch (e: Exception) {
                                Log.e("OverlayService", "Failed to update layout parameters", e)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                triggerScreenTranslation()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            try {
                windowManager.addView(floatingView, params)
                isFloatingWidgetVisible = true
                Log.d("OverlayService", "Floating widget spawned successfully.")
            } catch (e: Exception) {
                Log.e("OverlayService", "Failed to add floating view to WindomManager: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to create floating overlay layout: ${e.message}", e)
            Toast.makeText(this, "Yüzen overlay başlatılamadı! Lütfen izinleri kontrol edin.", Toast.LENGTH_LONG).show()
        }
    }

    private var lastTriggerTime = 0L

    private fun triggerScreenTranslation() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 1500) {
            Log.d("OverlayService", "Debounced screen capture request to avoid overlapping triggers.")
            return
        }
        lastTriggerTime = now
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        startActivity(intent)
    }

    private fun processScreenTranslation(bitmap: Bitmap) {
        showLoadingOverlay()
        
        serviceScope.launch {
            try {
                val prefs = getSharedPreferences("manga_translator_prefs", Context.MODE_PRIVATE)
                val sourceLang = try {
                    val savedLangName = prefs.getString("source_lang", SourceLanguage.JAPANESE.name) ?: SourceLanguage.JAPANESE.name
                    SourceLanguage.valueOf(savedLangName)
                } catch (e: Exception) {
                    SourceLanguage.JAPANESE
                }
                val fontSizeMultiplier = try {
                    prefs.getFloat("font_size_multiplier", 1.0f)
                } catch (e: Exception) {
                    1.0f
                }
                val isOfflineMode = try {
                    prefs.getBoolean("is_offline_mode", false)
                } catch (e: Exception) {
                    false
                }
                val isDebugOverlayEnabled = try {
                    prefs.getBoolean("is_debug_overlay_enabled", false)
                } catch (e: Exception) {
                    false
                }
                val targetLang = try {
                    val savedTargetName = prefs.getString("target_lang", TargetLanguage.TURKISH.name) ?: TargetLanguage.TURKISH.name
                    TargetLanguage.valueOf(savedTargetName)
                } catch (e: Exception) {
                    TargetLanguage.TURKISH
                }

                val pipeline = TranslationPipeline(this@OverlayService)
                
                val result = pipeline.translateMangaPage(
                    inputBitmap = bitmap,
                    sourceLanguage = sourceLang,
                    fontSizeMultiplier = fontSizeMultiplier,
                    isOfflineMode = isOfflineMode,
                    isDebugMode = isDebugOverlayEnabled,
                    targetLang = targetLang
                )

                if (result.textBlocksCount == 0) {
                    hideLoadingOverlay()
                    Toast.makeText(this@OverlayService, "Ekran üzerinde çevrilecek metin tespit edilemedi!", Toast.LENGTH_LONG).show()
                } else {
                    hideLoadingOverlay()
                    showTranslationResultOverlay(result)
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Background page translation failed", e)
                hideLoadingOverlay()
                Toast.makeText(this@OverlayService, "Çeviri hatası: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLoadingOverlay() {
        if (loadingOverlay != null) return
        
        try {
            val context = this
            loadingOverlay = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0x99000000.toInt()) 
                
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(64, 48, 64, 48)
                    
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 32f
                        setColor(0xFF1E293B.toInt()) 
                        setStroke(3, 0xFF00ADB5.toInt()) 
                    }
                    background = bg
                    elevation = 20f
                    
                    val spinner = android.widget.ProgressBar(context).apply {
                        indeterminateDrawable?.setColorFilter(0xFF00ADB5.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    addView(spinner, LinearLayout.LayoutParams(120, 120))
                    
                    val spacer = View(context)
                    addView(spacer, LinearLayout.LayoutParams(1, 24))
                    
                    val label = TextView(context).apply {
                        text = "Ekran Çevirisi Yapılıyor...\nLütfen Bekleyin"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 15f
                        gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    addView(label, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(card, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(loadingOverlay, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to show loading overlay", e)
        }
    }

    private fun hideLoadingOverlay() {
        loadingOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error removing loading overlay", e)
            }
            loadingOverlay = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showTranslationResultOverlay(result: TranslationPipeline.TranslationResult) {
        if (translationResultOverlay != null) {
            hideTranslationResultOverlay()
        }

        try {
            val context = this
            var showingOriginal = false

            translationResultOverlay = RelativeLayout(context).apply {
                setBackgroundColor(0xFF000000.toInt()) 

                val imageView = ImageView(context).apply {
                    setImageBitmap(result.translatedBitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                
                val imageParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
                addView(imageView, imageParams)

                val headerLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32, 24, 32, 24)
                    setBackgroundColor(0xD91E293B.toInt()) 
                    
                    val minHeight48dp = (48 * resources.displayMetrics.density).toInt()
                    
                    val closeButton = Button(context).apply {
                        text = "✕ Kapat"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 13f
                        setPadding(24, 12, 24, 12)
                        
                        val closeBg = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 24f
                            setColor(0xFFEF4444.toInt()) 
                        }
                        background = closeBg
                        minimumHeight = minHeight48dp
                        minimumWidth = (80 * resources.displayMetrics.density).toInt()
                        
                        setOnClickListener {
                            hideTranslationResultOverlay()
                        }
                    }
                    
                    val labelView = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setPadding(24, 0, 24, 0)
                        
                        val title = TextView(context).apply {
                            text = "Manga Ekran Çevirisi"
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 14f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        addView(title)
                        
                        val subTitle = TextView(context).apply {
                            text = "${result.textBlocksCount} balon akıllıca çevrildi"
                            setTextColor(0xFF00ADB5.toInt())
                            textSize = 11f
                        }
                        addView(subTitle)
                    }

                    val toggleButton = Button(context).apply {
                        text = "Orijinal Göster"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 12f
                        setPadding(24, 12, 24, 12)
                        
                        val toggleBg = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 24f
                            setColor(0xFF00ADB5.toInt()) 
                        }
                        background = toggleBg
                        minimumHeight = minHeight48dp
                        minimumWidth = (100 * resources.displayMetrics.density).toInt()

                        setOnClickListener {
                            showingOriginal = !showingOriginal
                            if (showingOriginal) {
                                imageView.setImageBitmap(result.originalBitmap)
                                text = "Çeviri Göster"
                                toggleBg.setColor(0xFF475569.toInt()) 
                            } else {
                                imageView.setImageBitmap(result.translatedBitmap)
                                text = "Orijinal Göster"
                                toggleBg.setColor(0xFF00ADB5.toInt())
                            }
                        }
                    }

                    val lpClose = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(closeButton, lpClose)

                    val lpLabel = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    addView(labelView, lpLabel)

                    val lpToggle = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(toggleButton, lpToggle)
                }

                val headerParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                }
                addView(headerLayout, headerParams)
                
                setupGestureMatrix(imageView)
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            translationResultOverlay!!.isFocusableInTouchMode = true
            translationResultOverlay!!.requestFocus()
            translationResultOverlay!!.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    hideTranslationResultOverlay()
                    true
                } else false
            }

            try {
                windowManager.addView(translationResultOverlay, params)
            } catch (e: Exception) {
                Log.e("OverlayService", "Failed to add translation layout to WindowManager", e)
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to display full screen translation overlay", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureMatrix(imageView: ImageView) {
        val matrix = android.graphics.Matrix()
        val savedMatrix = android.graphics.Matrix()
        
        var mode = 0 
        val start = android.graphics.PointF()
        val mid = android.graphics.PointF()
        var oldDist = 1f
        
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
        
        imageView.setOnTouchListener { v, event ->
            imageView.scaleType = ImageView.ScaleType.MATRIX
            
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(event.x, event.y)
                    mode = 1 
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = getSpacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        getMidPoint(mid, event)
                        mode = 2 
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = 0 
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) { 
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - start.x, event.y - start.y)
                    } else if (mode == 2) { 
                        val newDist = getSpacing(event)
                        if (newDist > 10f) {
                            val values = FloatArray(9)
                            savedMatrix.getValues(values)
                            val currentScale = values[android.graphics.Matrix.MSCALE_X]
                            var scale = newDist / oldDist
                            
                            // Clamp scale factor between 0.5 and 5.0 to avoid losing focus of the comic
                            if (currentScale * scale < 0.5f) {
                                scale = 0.5f / currentScale
                            } else if (currentScale * scale > 5.0f) {
                                scale = 5.0f / currentScale
                            }
                            
                            matrix.set(savedMatrix)
                            matrix.postScale(scale, scale, mid.x, mid.y)
                        }
                    }
                }
            }
            imageView.imageMatrix = matrix
            true
        }
    }
    
    private fun getSpacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }
    
    private fun getMidPoint(point: android.graphics.PointF, event: MotionEvent) {
        val avgX = (event.getX(0) + event.getX(1)) / 2
        val avgY = (event.getY(0) + event.getY(1)) / 2
        point.set(avgX, avgY)
    }

    private fun hideTranslationResultOverlay() {
        translationResultOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error removing translation overlay", e)
            }
            translationResultOverlay = null
        }
    }

    private fun hideFloatingWidget() {
        floatingView?.let {
            if (isFloatingWidgetVisible) {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e("OverlayService", "Error removing overlay view: ${e.message}")
                }
                floatingView = null
                isFloatingWidgetVisible = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Manga Translator Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val closeIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_HIDE_WIDGET
        }
        val closePendingIntent = PendingIntent.getService(
            this,
            1,
            closeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val translateIntent = Intent(this, ScreenCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        val translatePendingIntent = PendingIntent.getActivity(
            this,
            2,
            translateIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Çevirmeni")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(translatePendingIntent) // Launch screen translation directly on notification click
            .addAction(android.R.drawable.ic_menu_search, "Bulunduğun Ekranı Çevir", translatePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", closePendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        hideFloatingWidget()
        hideLoadingOverlay()
        hideTranslationResultOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
