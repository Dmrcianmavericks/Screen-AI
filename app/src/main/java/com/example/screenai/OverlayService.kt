package com.example.screenai

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var answerText: TextView
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val bitmapLock = Object()
    private var latestBitmap: Bitmap? = null
    private var apiKey: String = ""
    private var fastModel: String? = null
    private var isScanning = false
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("DATA")
        }

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        apiKey = intent.getStringExtra("API_KEY")?.trim() ?: ""

        bgThread = HandlerThread("ScreenCaptureThread").apply { start() }
        bgHandler = Handler(bgThread.looper)

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupAndStop()
            }
        }, Handler(Looper.getMainLooper()))

        setupScreenReader()
        setupFloatingOverlay()

        thread {
            fastModel = discoverFastestModelForKey()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "ScreenAIChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen AI", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Screen AI Active")
                .setContentText("Tap Scan to read screen")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Screen AI Active").build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupScreenReader() {
        val metrics = resources.displayMetrics
        // 75% resolution preserves crisp text for accurate quiz reading
        val width = (metrics.widthPixels * 0.75).toInt()
        val height = (metrics.heightPixels * 0.75).toInt()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val fullBitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height, Bitmap.Config.ARGB_8888
                    )
                    fullBitmap.copyPixelsFromBuffer(buffer)
                    
                    // Crop to the top 75% of the screen where quiz questions and options are always displayed
                    val quizCrop = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, (image.height * 0.75).toInt())
                    fullBitmap.recycle()

                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = quizCrop
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }
        }, bgHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenAI", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )
    }

    private fun setupFloatingOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 140
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E6000000")) // Solid dark contrast pill
            cornerRadius = 36f
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding(18, 10, 22, 10)
            gravity = Gravity.CENTER_VERTICAL
        }

        val scanButton = Button(this).apply {
            text = "SCAN"
            textSize = 11f
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                if (!isScanning) captureAndAskGemini()
            }
        }

        answerText = TextView(this).apply {
            text = "Ready"
            setTextColor(Color.parseColor("#00FF88")) // Bright green answer text
            textSize = 14f
            setPadding(22, 0, 22, 0)
            minWidth = 220
            maxWidth = 680
            maxLines = 2
        }

        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setPadding(12, 4, 8, 4)
            setOnClickListener { cleanupAndStop() }
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        answerText.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                else -> false
            }
        }

        overlayView.addView(scanButton)
        overlayView.addView(answerText)
        overlayView.addView(closeButton)

        windowManager.addView(overlayView, params)
    }

    private fun captureAndAskGemini() {
        isScanning = true
        answerText.text = "..."
        overlayView.visibility = View.INVISIBLE

        // 60ms hide time so screen updates instantly without blocking timer
        Handler(Looper.getMainLocals() ?: Looper.getMainLooper()).postDelayed({
            overlayView.visibility = View.VISIBLE

            val snap: Bitmap? = synchronized(bitmapLock) {
                latestBitmap?.let { it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            if (snap == null) {
                answerText.text = "Tap Scan again"
                isScanning = false
                return@postDelayed
            }

            thread {
                val base64Img = bitmapToBase64(snap)
                snap.recycle()
                val reply = askFastGemini(base64Img)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 60)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun discoverFastestModelForKey(): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return null

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val modelsArray = json.getJSONArray("models")
            val available = mutableListOf<String>()

            for (i in 0 until modelsArray.length()) {
                val m = modelsArray.getJSONObject(i)
                val name = m.getString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent") &&
                    !name.contains("tts") && !name.contains("image") &&
                    !name.contains("audio") && !name.contains("live") &&
                    !name.contains("transcribe") && !name.contains("robotics")
                ) {
                    available.add(name)
                }
            }

            val speedPriority = listOf(
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-3.6-flash",
                "gemini-3.8-flash",
                "gemini-3-flash-preview",
                "gemini-2.5-flash-lite",
                "gemini-2.5-flash"
            )
            for (pref in speedPriority) {
                if (available.contains(pref)) return pref
            }
            available.firstOrNull { it.contains("lite") } ?: available.firstOrNull { it.contains("flash") } ?: available.firstOrNull()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun askFastGemini(base64Image: String): String {
        val candidates = mutableListOf<String>()
        if (fastModel == null) {
            fastModel = discoverFastestModelForKey()
        }
        fastModel?.let { candidates.add(it) }

        listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash").forEach {
            if (!candidates.contains(it)) candidates.add(it)
        }

        var lastError = "Retry"
        for (model in candidates) {
            var res = callModel(model, base64Image, disableThinking = true)
            if (res.startsWith("ERR:(400)")) {
                res = callModel(model, base64Image, disableThinking = false)
            }
            if (!res.startsWith("ERR:")) {
                fastModel = model
                return res
            }
            lastError = res.removePrefix("ERR:")
            if (lastError.contains("403") || lastError.contains("429")) break
        }
        return lastError
    }

    private fun callModel(modelName: String, base64Image: String, disableThinking: Boolean): String {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 4500
                readTimeout = 4500
                doOutput = true
            }

            // Direct, accurate instruction for objective tests and quizzes
            val prompt = "Analyze this quiz question and options. State the correct option letter and exact answer clearly (e.g., 'B) precise'). Maximum 4 words. No explanation."

            val inlineData = JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", base64Image)
            }
            val parts = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().put("inlineData", inlineData))
            }
            val contents = JSONArray().put(JSONObject().put("parts", parts))

            val genConfig = JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 100)
                if (disableThinking) {
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
            }

            val payload = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", genConfig)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) return "No answer"

                val contentObj = candidates.getJSONObject(0).optJSONObject("content") ?: return "No text"
                val resParts = contentObj.optJSONArray("parts") ?: return "No parts"

                var finalAnswer = ""
                for (i in 0 until resParts.length()) {
                    val p = resParts.getJSONObject(i)
                    if (!p.optBoolean("thought", false) && p.has("text")) {
                        val t = p.getString("text").trim()
                        if (t.isNotEmpty()) finalAnswer = t
                    }
                }

                if (finalAnswer.isEmpty()) "Retry" else finalAnswer.replace("\n", " ").replace("*", "")
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                val msg = try { JSONObject(errBody).getJSONObject("error").getString("message") } catch (e: Exception) { "HTTP $code" }
                "ERR:($code) ${msg.take(25)}"
            }
        } catch (e: Exception) {
            "ERR:Timeout"
        } finally {
            conn?.disconnect()
        }
    }

    private fun cleanupAndStop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        if (::bgThread.isInitialized) bgThread.quitSafely()
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.removeView(overlayView)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndStop()
    }
}
