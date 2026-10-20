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
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private var overlayOpacityPercent: Int = 75

    private val pairStepCounter = AtomicInteger(0)
    private val geminiModelCache = ConcurrentHashMap<String, String>()
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
    private val legacyThinkingModels = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val noThinkingModels = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var networkPool: ExecutorService = Executors.newCachedThreadPool()

    @Volatile private var isScanning = false
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "20")

        if (networkPool.isShutdown) {
            networkPool = Executors.newCachedThreadPool()
        }

        cleanupSessionOnly()
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

        overlayOpacityPercent = intent.getIntExtra("OPACITY_PERCENT", 75).coerceIn(5, 100)

        val rawKeys = intent.getStringExtra("API_KEY") ?: ""
        apiKeys = rawKeys.split(",", "\n", " ")
            .map { it.trim().replace("\"", "").replace("'", "") }
            .filter { it.length > 12 && !it.startsWith("gsk_") }
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

        for ((idx, k) in apiKeys.withIndex()) {
            networkPool.execute {
                if (idx > 0) {
                    try { Thread.sleep(idx * 60L) } catch (_: Exception) {}
                }
                discoverGeminiModelOnStartup(k)?.let { geminiModelCache[k] = it }
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
                .setContentText("Smart Reasoning Mode Active")
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
        // 0.60 scale keeps fill-in-the-blank lines, math signs, and text sharp
        val width = (metrics.widthPixels * 0.60).toInt()
        val height = (metrics.heightPixels * 0.60).toInt()
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

                    val topY = (image.height * 0.12).toInt()
                    val cropH = (image.height * 0.70).toInt()
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, topY, image.width, cropH)
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
            y = 110
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 36f
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding(14, 6, 18, 6)
            gravity = Gravity.CENTER_VERTICAL
            alpha = overlayOpacityPercent / 100f
        }

        val btnDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#44000000"))
            setStroke(2, Color.parseColor("#88CCCCCC"))
            cornerRadius = 20f
        }

        val scanButton = Button(this).apply {
            text = "SCAN"
            textSize = 11f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            background = btnDrawable
            setPadding(16, 4, 16, 4)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                if (!isScanning) captureAndAskAI()
            }
        }

        answerText = TextView(this).apply {
            text = "Ready (${apiKeys.size} keys)"
            setTextColor(Color.parseColor("#00FF88"))
            setShadowLayer(7f, 0f, 0f, Color.BLACK)
            textSize = 14f
            setPadding(20, 0, 20, 0)
            minWidth = 200
            maxWidth = 680
            maxLines = 2
        }

        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
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
        overlayView.invalidate()

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

            networkPool.execute {
                val base64Img = bitmapToBase64(snap)
                snap.recycle()
                val reply = executeFastPairWithInstantFailover(base64Img)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 40)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 62, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun pickNextHealthyKeys(count: Int, exclude: Set<String> = emptySet()): List<String> {
        val total = apiKeys.size
        if (total == 0) return emptyList()
        val now = System.currentTimeMillis()
        val start = pairStepCounter.getAndAdd(count)

        val ready = mutableListOf<String>()
        val cooling = mutableListOf<String>()
        for (i in 0 until total) {
            val k = apiKeys[(start + i) % total]
            if (exclude.contains(k)) continue
            if (now >= (keyCooldownUntil[k] ?: 0L)) {
                ready.add(k)
            } else {
                cooling.add(k)
            }
        }
        return (ready + cooling).take(count)
    }

    private fun executeFastPairWithInstantFailover(base64Image: String): String {
        val total = apiKeys.size
        if (total == 1) {
            val res = callGeminiFast(apiKeys[0], base64Image)
            return if (!res.startsWith("ERR:")) res else "Err (${res.removePrefix("ERR:")})"
        }

        val initialPair = pickNextHealthyKeys(2)
        val usedKeys = Collections.synchronizedSet(HashSet<String>(initialPair))

        val winner = AtomicReference<String?>(null)
        val lastErr = AtomicReference("Time")
        val solved = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val remaining = AtomicInteger(initialPair.size)

        for ((idx, firstKey) in initialPair.withIndex()) {
            networkPool.execute {
                if (idx == 1) {
                    try { Thread.sleep(100L) } catch (_: Exception) {}
                }

                var currentKey: String? = firstKey
                var attempts = 0

                while (currentKey != null && attempts < 2 && !solved.get()) {
                    attempts++
                    val res = callGeminiFast(currentKey, base64Image)
                    if (!res.startsWith("ERR:")) {
                        if (solved.compareAndSet(false, true)) {
                            winner.set(res)
                            latch.countDown()
                        }
                        break
                    } else {
                        val errCode = res.removePrefix("ERR:")
                        if (errCode != "Time" || lastErr.get() == "Time") {
                            lastErr.set(errCode)
                        }
                        currentKey = if (!solved.get() && usedKeys.size < total) {
                            synchronized(usedKeys) {
                                val next = pickNextHealthyKeys(1, usedKeys).firstOrNull()
                                if (next != null) usedKeys.add(next)
                                next
                            }
                        } else {
                            null
                        }
                    }
                }

                if (remaining.decrementAndGet() == 0) {
                    latch.countDown()
                }
            }
        }

        latch.await(6000, TimeUnit.MILLISECONDS)
        return winner.get() ?: "Err (${lastErr.get()})"
    }

    private fun discoverGeminiModelOnStartup(key: String): String? {
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$encodedKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return null

            val responseText = conn.inputStream.use { it.bufferedReader().readText() }
            val json = JSONObject(responseText)
            val arr = json.getJSONArray("models")
            val unlocked = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val name = m.getString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent")) unlocked.add(name)
            }

            // Prioritizes the smartest full Flash models first so trick questions are solved accurately
            val preferred = listOf(
                "gemini-3.8-flash",
                "gemini-3.7-flash",
                "gemini-3.6-flash",
                "gemini-3.5-flash",
                "gemini-2.5-flash",
                "gemini-2.0-flash",
                "gemini-3.5-flash-lite",
                "gemini-2.5-flash-lite"
            )
            for (p in preferred) {
                if (unlocked.contains(p)) return p
            }
            unlocked.firstOrNull { it.contains("flash") && !it.contains("lite") && !it.contains("tts") && !it.contains("image") }
                ?: unlocked.firstOrNull { it.contains("flash") && !it.contains("tts") && !it.contains("image") }
        } catch (e: Exception) {
            null
        }
    }

    private fun callGeminiFast(key: String, base64Image: String): String {
        val model = geminiModelCache[key] ?: "gemini-2.5-flash"

        // Determine thinking mode: 0 = Modern (thinkingLevel/thinkingBudget), 1 = Legacy budget, 2 = No thinkingConfig
        val mode = when {
            noThinkingModels.contains(model) -> 2
            legacyThinkingModels.contains(model) -> 1
            else -> 0
        }

        var res = sendGeminiHttp(key, model, base64Image, thinkingMode = mode)

        if (res == "ERR:400" && mode == 0) {
            legacyThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, thinkingMode = 1)
        }
        if (res == "ERR:400" && mode <= 1) {
            noThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, thinkingMode = 2)
        } else if (res == "ERR:404") {
            val nextModel = if (model == "gemini-2.5-flash") "gemini-3.5-flash-lite" else "gemini-2.5-flash"
            geminiModelCache[key] = nextModel
            res = sendGeminiHttp(key, nextModel, base64Image, thinkingMode = 0)
        }

        if (res.startsWith("ERR:")) {
            val code = res.removePrefix("ERR:")
            if (code == "429") {
                keyCooldownUntil[key] = System.currentTimeMillis() + 20_000L
            } else if (code == "403" || code == "401") {
                keyCooldownUntil[key] = System.currentTimeMillis() + 300_000L
            }
        }
        return res
    }

    private fun sendGeminiHttp(key: String, modelName: String, base64Image: String, thinkingMode: Int): String {
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$encodedKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 3000
                readTimeout = 5500
                doOutput = true
            }

            val prompt = "Carefully solve the quiz question on screen. Watch out for trick questions: 1) ANTONYM (opposite) vs SYNONYM (similar), 2) NOT / EXCEPT / INCORRECT, 3) Grammar clues before/after blanks (like 'than' = comparative, 'the' = superlative, subject-verb agreement), 4) Math order of operations (BODMAS) and signs. After thinking, output ONLY the correct option letter and exact short answer (example: 'D) reject' or 'B) better'). NO explanation. Max 4 words."

            val inlineData = JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", base64Image)
            }
            val parts = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().put("inlineData", inlineData))
            }
            val contents = JSONArray().put(JSONObject().put("parts", parts))

            // CRITICAL: maxOutputTokens covers BOTH internal thought tokens + the 4-word answer.
            // Setting 800 ensures internal thinking on trick questions NEVER gets cut off mid-thought!
            val genConfig = JSONObject().apply {
                put("maxOutputTokens", 800)
                when (thinkingMode) {
                    0 -> {
                        if (modelName.startsWith("gemini-3")) {
                            // Official Gemini 3.x fast reasoning level
                            put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
                        } else if (modelName.contains("lite")) {
                            // Gemini 2.5 Flash-Lite requires minimum budget of 512
                            put("thinkingConfig", JSONObject().put("thinkingBudget", 512))
                        } else {
                            // Gemini 2.5 Flash fast reasoning budget (~0.35s)
                            put("thinkingConfig", JSONObject().put("thinkingBudget", 256))
                        }
                    }
                    1 -> {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 256))
                    }
                    // mode 2 omits thinkingConfig so the model uses its native default
                }
            }

            val payload = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", genConfig)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code == 200) {
                val responseText = conn.inputStream.use { it.bufferedReader().readText() }
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

                if (finalAnswer.isEmpty()) "ERR:Empty" else cleanShortOutput(finalAnswer)
            } else {
                conn.errorStream?.use { it.readBytes() }
                "ERR:$code"
            }
        } catch (e: SocketTimeoutException) {
            "ERR:Time"
        } catch (e: Exception) {
            "ERR:Net"
        }
    }

    private fun cleanShortOutput(raw: String): String {
        val singleLine = raw.replace("*", "").replace("\n", " ").trim()
        val match = Regex("""^([A-Da-d][\)\.\:]\s*\S+(?:\s+\S+){0,2})""").find(singleLine)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val words = singleLine.split(Regex("\\s+"))
        return if (words.size > 5) words.take(4).joinToString(" ") else singleLine
    }

    private fun cleanupSessionOnly() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        if (::bgThread.isInitialized) {
            try { bgThread.quitSafely() } catch (_: Exception) {}
        }
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }

    private fun cleanupAndStop() {
        cleanupSessionOnly()
        try { networkPool.shutdownNow() } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndStop()
    }
}
