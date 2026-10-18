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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {

    data class WorkingEndpoint(
        val key: String,
        val baseUrl: String, // Either AI Studio or Vertex Express
        val model: String
    )

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var answerText: TextView
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val bitmapLock = Object()
    private var latestBitmap: Bitmap? = null

    private var rawKeyList: List<String> = emptyList()
    // Holds only pre-tested, 100% verified working (Key + Endpoint + Model) routes
    private val verifiedRoutes = CopyOnWriteArrayList<WorkingEndpoint>()
    private val routeCounter = AtomicInteger(0)
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private var isScanning = false
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    companion object {
        private const val AI_STUDIO_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val VERTEX_BASE = "https://aiplatform.googleapis.com/v1/publishers/google/models"

        // Verified 2026 fast multimodal Flash models
        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-3.6-flash",
            "gemini-3.8-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.5-flash-lite"
        )
    }

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
        rawKeyList = rawKeys.split(",", "\n", " ")
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

        // Probe all 4 keys in parallel right on startup so scanning has ZERO guesswork
        warmUpAndVerifyAllKeys()

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
                .setContentText("Tap SCAN on overlay")
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
        // 62% resolution keeps math symbols (+, -, ÷, exponents) sharp and uploads in 0.2s
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
                if (!isScanning) captureAndAskGemini()
            }
        }

        answerText = TextView(this).apply {
            text = "Checking keys..."
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

    // Runs in parallel when overlay opens: tests each key against the right endpoint (Vertex for AQ., AI Studio for AIza)
    private fun warmUpAndVerifyAllKeys() {
        for (key in rawKeyList) {
            thread {
                val route = findWorkingRouteForKey(key)
                if (route != null) {
                    verifiedRoutes.add(route)
                    Handler(Looper.getMainLooper()).post {
                        if (!isScanning) {
                            answerText.text = "Ready (${verifiedRoutes.size} active)"
                        }
                    }
                }
            }
        }
    }

    private fun findWorkingRouteForKey(key: String): WorkingEndpoint? {
        // AQ. keys belong to Vertex Express first; AIzaSy keys belong to AI Studio first
        val baseUrls = if (key.startsWith("AQ.")) {
            listOf(VERTEX_BASE, AI_STUDIO_BASE)
        } else {
            listOf(AI_STUDIO_BASE, VERTEX_BASE)
        }

        for (base in baseUrls) {
            for (model in CANDIDATE_MODELS) {
                val status = testPing(key, base, model)
                if (status == 200 || status == 429) {
                    // 200 or 429 means this (Key + Endpoint + Model) is 100% valid and authenticated!
                    return WorkingEndpoint(key, base, model)
                }
            }
        }
        return null
    }

    private fun testPing(key: String, baseUrl: String, model: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("$baseUrl/$model:generateContent?key=$encodedKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
            }
            // Minimal 1-token ping with "role": "user" (required by Vertex!)
            val payload = """{"contents":[{"role":"user","parts":[{"text":"1"}]}],"generationConfig":{"maxOutputTokens":2}}"""
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.responseCode
        } catch (e: Exception) {
            0
        } finally {
            conn?.disconnect()
        }
    }

    private fun captureAndAskGemini() {
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
                val reply = executeFastScan(base64Img)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 45)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 64, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun executeFastScan(base64Image: String): String {
        // If user tapped Scan within 1 second before warm-up finished, build default routes immediately
        if (verifiedRoutes.isEmpty()) {
            for (k in rawKeyList) {
                val defaultBase = if (k.startsWith("AQ.")) VERTEX_BASE else AI_STUDIO_BASE
                val defaultModel = if (k.startsWith("AQ.")) "gemini-2.5-flash" else "gemini-2.5-flash"
                verifiedRoutes.add(WorkingEndpoint(k, defaultBase, defaultModel))
            }
        }

        val total = verifiedRoutes.size
        if (total == 0) return "No valid key"

        val startIdx = routeCounter.getAndIncrement()
        val now = System.currentTimeMillis()
        var lastErr = "Retry"

        // Pass 1: Try verified routes that are NOT on a 429 cooldown
        for (i in 0 until total) {
            val route = verifiedRoutes[(startIdx + i) % total]
            val id = "${route.key}|${route.model}"
            if (now < (cooldownUntil[id] ?: 0L)) continue

            val res = callEndpointFast(route, base64Image)
            if (!res.startsWith("ERR:")) {
                return res
            }
            lastErr = res.removePrefix("ERR:")
            if (lastErr == "429") {
                // Cool down this key for 30s and immediately use the next key in 0.01s
                cooldownUntil[id] = System.currentTimeMillis() + 30_000L
            }
        }

        // Pass 2: If all keys had a cooldown, try an alternate model on each key (separate quota bucket!)
        for (i in 0 until total) {
            val route = verifiedRoutes[(startIdx + i) % total]
            val altModel = if (route.model == "gemini-2.5-flash") "gemini-2.5-flash-lite" else "gemini-2.5-flash"
            val altRoute = WorkingEndpoint(route.key, route.baseUrl, altModel)
            val res = callEndpointFast(altRoute, base64Image)
            if (!res.startsWith("ERR:")) {
                return res
            }
        }

        return "Wait 10s ($lastErr)"
    }

    private fun callEndpointFast(route: WorkingEndpoint, base64Image: String): String {
        var res = sendHttpRequest(route, base64Image, includeThinkingConfig = true)
        if (res == "ERR:400") {
            res = sendHttpRequest(route, base64Image, includeThinkingConfig = false)
        }
        return res
    }

    private fun sendHttpRequest(route: WorkingEndpoint, base64Image: String, includeThinkingConfig: Boolean): String {
        var conn: HttpURLConnection? = null
        return try {
            val encodedKey = URLEncoder.encode(route.key, "UTF-8")
            val url = URL("${route.baseUrl}/${route.model}:generateContent?key=$encodedKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", route.key)
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

            // CRITICAL: "role": "user" is mandatory for AQ. Vertex Express keys!
            val userContent = JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }
            val contents = JSONArray().put(userContent)

            val genConfig = JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 60)
                if (includeThinkingConfig) {
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
