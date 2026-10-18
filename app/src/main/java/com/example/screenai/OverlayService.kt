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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    private var apiKeys: List<String> = emptyList()
    private val turnCounter = AtomicInteger(0)
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
    private val geminiModelCache = ConcurrentHashMap<String, String>()

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

        val rawKeys = intent.getStringExtra("API_KEY") ?: ""
        apiKeys = rawKeys.split(",", "\n", " ")
            .map { it.trim() }
            .filter { it.length > 12 }
            .distinct()

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

        // Pre-check which exact Gemini model is unlocked for any AQ. or AIza key on startup
        thread {
            for (k in apiKeys) {
                if (!k.startsWith("gsk_")) {
                    discoverGeminiModel(k)?.let { geminiModelCache[k] = it }
                }
            }
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
                .setContentText("${apiKeys.size} Keys Loaded")
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
        // 62% resolution keeps math symbols (+, -, ÷, fractions) crisp and uploads in ~0.2s on 4G
        val width = (metrics.widthPixels * 0.62).toInt()
        val height = (metrics.heightPixels * 0.62).toInt()
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

                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, (image.height * 0.82).toInt())
                    fullBitmap.recycle()

                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = cropped
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
            y = 130
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E6000000"))
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
                if (!isScanning) captureAndAskAI()
            }
        }

        answerText = TextView(this).apply {
            text = "Ready (${apiKeys.size} keys)"
            setTextColor(Color.parseColor("#00FF88"))
            textSize = 14f
            setPadding(20, 0, 20, 0)
            minWidth = 200
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

    private fun captureAndAskAI() {
        if (apiKeys.isEmpty()) {
            answerText.text = "Add Key in App!"
            return
        }
        isScanning = true
        answerText.text = "..."
        overlayView.visibility = View.INVISIBLE

        Handler(Looper.getMainLooper()).postDelayed({
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
                val reply = executeMultiKeyScan(base64Img)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 40)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 64, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun executeMultiKeyScan(base64Image: String): String {
        val total = apiKeys.size
        val startIdx = turnCounter.getAndIncrement()
        val now = System.currentTimeMillis()
        var lastError = "Retry"

        // Pass 1: Try active keys not in cooldown
        for (i in 0 until total) {
            val key = apiKeys[(startIdx + i) % total]
            if (now < (keyCooldownUntil[key] ?: 0L)) continue

            val res = if (key.startsWith("gsk_")) {
                callGroqFast(key, base64Image)
            } else {
                callGeminiSmart(key, base64Image)
            }

            if (!res.startsWith("ERR:")) {
                return res
            }

            lastError = res.removePrefix("ERR:")
            if (lastError == "429") {
                // Cool down this key for 20s and immediately jump to the next key
                keyCooldownUntil[key] = System.currentTimeMillis() + 20_000L
            } else if (lastError == "403" || lastError == "401") {
                // Key is invalid or leaked, skip it for 5 minutes
                keyCooldownUntil[key] = System.currentTimeMillis() + 300_000L
            }
        }

        // Pass 2: If all keys had a brief 429 cooldown, force-try the next key in line anyway
        val fallbackKey = apiKeys[startIdx % total]
        val forceRes = if (fallbackKey.startsWith("gsk_")) {
            callGroqFast(fallbackKey, base64Image)
        } else {
            callGeminiSmart(fallbackKey, base64Image)
        }
        if (!forceRes.startsWith("ERR:")) return forceRes

        return "Err ($lastError)"
    }

    // GROQ ENGINE: Uses Llama 4 Maverick (400B flagship for better math) -> fallback to Llama 4 Scout
    private fun callGroqFast(groqKey: String, base64Image: String): String {
        val models = listOf(
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "meta-llama/llama-4-scout-17b-16e-instruct"
        )
        var lastCode = "Net"
        for (model in models) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.groq.com/openai/v1/chat/completions")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $groqKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Connection", "keep-alive")
                    connectTimeout = 3500
                    readTimeout = 3500
                    doOutput = true
                }

                val prompt = "Solve the math or quiz question on screen carefully. Reply ONLY with the correct option letter and final answer (example: 'B) precise' or 'C) 24'). Maximum 4 words. No explanation."

                val contentArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
                    })
                }

                val messages = JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArr)
                })

                val payload = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("temperature", 0.0)
                    put("max_tokens", 35)
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                val code = conn.responseCode
                if (code == 200) {
                    val res = JSONObject(conn.inputStream.bufferedReader().readText())
                    val text = res.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                        .replace("\n", " ")
                        .replace("*", "")
                    if (text.isNotEmpty()) return text
                } else {
                    lastCode = "$code"
                    if (code == 401 || code == 403 || code == 429) break
                }
            } catch (e: Exception) {
                lastCode = "Time"
            } finally {
                conn?.disconnect()
            }
        }
        return "ERR:$lastCode"
    }

    // GEMINI ENGINE: Discovers the exact unlocked October 2026 Flash model for AQ. or AIza keys
    private fun discoverGeminiModel(key: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$encodedKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return null

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.getJSONArray("models")
            val unlocked = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val name = m.getString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent")) unlocked.add(name)
            }

            // Current October 2026 AI Studio active models
            val preferred = listOf(
                "gemini-3.6-flash",
                "gemini-3.8-flash",
                "gemini-3.5-flash-lite",
                "gemini-2.5-flash",
                "gemini-3.1-flash-lite"
            )
            for (p in preferred) {
                if (unlocked.contains(p)) return p
            }
            unlocked.firstOrNull { it.contains("flash") && !it.contains("tts") && !it.contains("image") }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun callGeminiSmart(key: String, base64Image: String): String {
        val cached = geminiModelCache[key] ?: discoverGeminiModel(key)?.also { geminiModelCache[key] = it }
        // Tries discovered model first, then 3.6-flash, 3.5-flash-lite, and 3.8-flash
        val modelsToTry = listOfNotNull(
            cached,
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-2.5-flash"
        ).distinct()

        var lastCode = "Net"
        for (model in modelsToTry) {
            var res = sendGeminiHttp(key, model, base64Image, useThinkingConfig = true)
            if (res == "ERR:400") {
                res = sendGeminiHttp(key, model, base64Image, useThinkingConfig = false)
            }
            if (!res.startsWith("ERR:")) {
                geminiModelCache[key] = model
                return res
            }
            lastCode = res.removePrefix("ERR:")
            // If key itself is rate-limited (429) or blocked (403), stop trying models on this key and switch to next key!
            if (lastCode == "429" || lastCode == "403") break
        }
        return "ERR:$lastCode"
    }

    private fun sendGeminiHttp(key: String, modelName: String, base64Image: String, useThinkingConfig: Boolean): String {
        var conn: HttpURLConnection? = null
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$encodedKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 3500
                readTimeout = 3500
                doOutput = true
            }

            val prompt = "Solve the math or quiz question on screen accurately. Reply ONLY with the correct option letter and final answer (example: 'B) precise' or 'C) 24'). Maximum 4 words. No explanation."

            val inlineData = JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", base64Image)
            }
            val parts = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().put("inlineData", inlineData))
            }
            val contents = JSONArray().put(JSONObject().put("parts", parts))

            // Note: 'temperature' is omitted because Gemini 3.6/3.8 deprecated sampling parameters
            val genConfig = JSONObject().apply {
                put("maxOutputTokens", 80)
                if (useThinkingConfig) {
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
                val candidates = json.optJSONArray("candidates") ?: return "ERR:Empty"
                if (candidates.length() == 0) return "ERR:Empty"

                val contentObj = candidates.getJSONObject(0).optJSONObject("content") ?: return "ERR:Empty"
                val resParts = contentObj.optJSONArray("parts") ?: return "ERR:Empty"

                var finalAnswer = ""
                for (i in 0 until resParts.length()) {
                    val p = resParts.getJSONObject(i)
                    if (!p.optBoolean("thought", false) && p.has("text")) {
                        val t = p.getString("text").trim()
                        if (t.isNotEmpty()) finalAnswer = t
                    }
                }

                if (finalAnswer.isEmpty()) "ERR:Empty" else finalAnswer.replace("\n", " ").replace("*", "")
            } else {
                "ERR:$code"
            }
        } catch (e: Exception) {
            "ERR:Time"
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
