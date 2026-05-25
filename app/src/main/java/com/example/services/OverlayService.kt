package com.example.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var isFloatingWidgetVisible = false

    companion object {
        private const val CHANNEL_ID = "MangaTranslatorChannel"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_SHOW_WIDGET = "com.example.action.SHOW_WIDGET"
        const val ACTION_HIDE_WIDGET = "com.example.action.HIDE_WIDGET"
        const val ACTION_CAPTURE_SCREEN = "com.example.action.CAPTURE_SCREEN"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Manga Çevirmeni Arka Planda Çalışıyor"))
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
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        try {
            floatingView = FrameLayout(this).apply {
                // Outer container
                val sizePx = (56 * resources.displayMetrics.density).toInt()
                
                // Add a cute, sleek floating icon matching modern Dark UI
                val imageView = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_search)
                    setBackgroundColor(0xFF232D3F.toInt()) // Sleek slate dark background
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    clipToOutline = true
                    elevation = 10f
                    
                    // Simple programmatic circular shape drawing
                    val shape = android.graphics.drawable.GradientDrawable()
                    shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                    shape.setColor(0xFF00ADB5.toInt()) // Cyber cyan accent color
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

            // Implement dragging gesture on floating service button
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

                            // Check if drag distance exceeds threshold (to avoid jittery tap signals)
                            if (deltaX * deltaX + deltaY * deltaY > 100) {
                                isDragging = true
                            }

                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            windowManager.updateViewLayout(floatingView, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                // Trigger pipeline trigger action - we broadcast or trigger an action
                                triggerScreenTranslation()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager.addView(floatingView, params)
            isFloatingWidgetVisible = true
            Log.d("OverlayService", "Floating widget spawned successfully.")
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to create floating overlay layout: ${e.message}", e)
            Toast.makeText(this, "Yüzen overlay başlatılamadı! Lütfen izinleri kontrol edin.", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerScreenTranslation() {
        Toast.makeText(this, "Ekran Yakalanıyor... Lütfen bekleyin.", Toast.LENGTH_SHORT).show()
        
        // Broadcast to main activity to handle MediaProjection screen grabbing or show picker
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            action = ACTION_CAPTURE_SCREEN
        }
        startActivity(intent)
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
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val closeIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_HIDE_WIDGET
        }
        val closePendingIntent = PendingIntent.getService(
            this,
            1,
            closeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Çevirmeni")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", closePendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        hideFloatingWidget()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
