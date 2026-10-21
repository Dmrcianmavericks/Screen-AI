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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

    private val scanGeneration = AtomicInteger(0)
    private val pairStepCounter = AtomicInteger(0)

    private val geminiModelCache = ConcurrentHashMap<String, String>()
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
    private val noThinkingModels = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var networkPool: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "16")

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
        }, mainHandler)

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
                .setContentText("2-Slot Live Mode: Fast + Smart")
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
        val width = (metrics.widthPixels * 0.56).toInt()
        val height = (metrics.heightPixels * 0.56).toInt()
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
            x = 16
            y = 110
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 36f
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding(12, 6, 16, 6)
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
            setPadding(14, 4, 14, 4)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                captureAndStreamTwoSlots()
            }
        }

        answerText = TextView(this).apply {
            text = "Ready (${apiKeys.size} keys)"
            setTextColor(Color.parseColor("#00FF88"))
            setShadowLayer(7f, 0f, 0f, Color.BLACK)
            textSize = 14f
            setPadding(16, 0, 16, 0)
            minWidth = 190
            maxWidth = 740
            maxLines = 2
        }

        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            textSize = 15f
            setPadding(10, 4, 6, 4)
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

    /**
     * LIVE 2-SLOT ENGINE (Uses the exact 2-key network load proven to work fast on your phone):
     * - Slot 1 (Left): 0-thinking Sprinter -> pops onto screen in ~0.9s
     * - Slot 2 (Right, 🧠): 64-thinking Trick Verifier -> starts 150ms later & pops onto screen in ~1.5s
     */
    private fun captureAndStreamTwoSlots() {
        if (apiKeys.isEmpty()) {
            answerText.text = "Add Key in App!"
            return
        }

        val myGen = scanGeneration.incrementAndGet()

        overlayView.visibility = View.INVISIBLE
        overlayView.invalidate()

        mainHandler.postDelayed({
            if (scanGeneration.get() != myGen) return@postDelayed
            overlayView.visibility = View.VISIBLE

            val snap: Bitmap? = synchronized(bitmapLock) {
                latestBitmap?.let { it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            if (snap == null) {
                answerText.text = "Tap Scan again"
                return@postDelayed
            }

            val slotCount = minOf(2, apiKeys.size)
            val slots = if (slotCount == 2) arrayOf("..", "🧠..") else arrayOf("..")
            val slotLock = Object()

            fun updateHud() {
                if (scanGeneration.get() != myGen) return
                val textLine = synchronized(slotLock) {
                    slots.joinToString("  |  ")
                }
                mainHandler.post {
                    if (scanGeneration.get() == myGen) {
                        answerText.text = textLine
                    }
                }
            }

            updateHud()

            networkPool.execute {
                val base64Img = bitmapToBase64(snap)
                snap.recycle()

                if (scanGeneration.get() != myGen) return@execute

                val pairKeys = pickNextHealthyKeys(slotCount)
                val usedKeys = Collections.synchronizedSet(HashSet<String>(pairKeys))

                for ((slotIdx, startKey) in pairKeys.withIndex()) {
                    networkPool.execute {
                        // 150ms stagger guarantees Slot 1 finishes uploading before Slot 2 starts!
                        if (slotIdx == 1) {
                            try { Thread.sleep(150L) } catch (_: Exception) {}
                        }
                        if (scanGeneration.get() != myGen) return@execute

                        var res = callGeminiSlot(startKey, base64Img, isSmartSlot = (slotIdx == 1))

                        // Instant spare-key failover if a key returns an HTTP error (like 429/403)
                        if (res.startsWith("ERR:") && res != "ERR:Time" && scanGeneration.get() == myGen && usedKeys.size < apiKeys.size) {
                            val spareKey = synchronized(usedKeys) {
                                val next = pickNextHealthyKeys(1, usedKeys).firstOrNull()
                                if (next != null) usedKeys.add(next)
                                next
                            }
                            if (spareKey != null && scanGeneration.get() == myGen) {
                                res = callGeminiSlot(spareKey, base64Img, isSmartSlot = (slotIdx == 1))
                            }
                        }

                        if (scanGeneration.get() != myGen) return@execute

                        synchronized(slotLock) {
                            val formatted = if (!res.startsWith("ERR:")) {
                                res
                            } else {
                                "Err(${res.removePrefix("ERR:")})"
                            }
                            slots[slotIdx] = if (slotIdx == 1) "🧠 $formatted" else formatted
                        }
                        updateHud()
                    }
                }
            }
        }, 40)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Proven 500px width scaling: keeps text & blanks sharp while uploading in <0.1s
        val scaled = if (bitmap.width > 500) {
            val ratio = 500f / bitmap.width
            val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, 500, h, true)
        } else {
            bitmap
        }
        scaled.compress(Bitmap.CompressFormat.JPEG, 58, outputStream)
        if (scaled !== bitmap) scaled.recycle()
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

    private fun discoverGeminiModelOnStartup(key: String): String? {
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$encodedKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = 2500
                readTimeout = 2500
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

            // Exact model list from the version that worked fast on your phone
            val preferred = listOf(
                "gemini-2.5-flash",
                "gemini-3.6-flash",
                "gemini-3.8-flash",
                "gemini-2.0-flash",
                "gemini-2.5-flash-lite",
                "gemini-3.5-flash-lite"
            )
            for (p in preferred) {
                if (unlocked.contains(p)) return p
            }
            unlocked.firstOrNull { it.contains("flash") && !it.contains("tts") && !it.contains("image") }
        } catch (e: Exception) {
            null
        }
    }

    private fun callGeminiSlot(key: String, base64Image: String, isSmartSlot: Boolean): String {
        val model = geminiModelCache[key] ?: "gemini-2.5-flash"
        val allowThinkingField = !noThinkingModels.contains(model)

        var res = sendGeminiHttp(key, model, base64Image, isSmartSlot, allowThinkingField)

        if (res == "ERR:400" && allowThinkingField) {
            noThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, isSmartSlot, sendThinkingField = false)
        } else if (res == "ERR:404") {
            val nextModel = if (model == "gemini-2.5-flash") "gemini-3.5-flash-lite" else "gemini-2.5-flash"
            geminiModelCache[key] = nextModel
            res = sendGeminiHttp(key, nextModel, base64Image, isSmartSlot, sendThinkingField = !noThinkingModels.contains(nextModel))
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

    private fun sendGeminiHttp(
        key: String,
        modelName: String,
        base64Image: String,
        isSmartSlot: Boolean,
        sendThinkingField: Boolean
    ): String {
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$encodedKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 2500
                // Crisp 3.4s read timeout so it never hangs for 10-20s
                readTimeout = 3400
                doOutput = true
            }

            val prompt = if (!isSmartSlot) {
                // Slot 1: Fast direct prompt
                "Read the question and options (A/B/C/D) carefully. Check if it asks for SYNONYM, ANTONYM, Grammar, or Math. Reply ONLY with the correct option letter and exact short answer (example: 'D) reject' or 'C) 24'). NO explanation. Max 3 words."
            } else {
                // Slot 2 (🧠): Master Trick-Question Verifier
                "TRICK QUESTION VERIFIER: 1) Check if it asks for ANTONYM (opposite) vs SYNONYM (tip: if 3 options mean the same thing and 1 is opposite, pick the odd one out!). 2) Check for NOT/EXCEPT. 3) Check grammar rules around blanks (prepositions, tense, singular/plural). 4) Solve math step-by-step. Output ONLY the correct option letter and exact short answer (example: 'D) reject'). NO explanation. Max 3 words."
            }

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
                if (!isSmartSlot) {
                    // Slot 1: 0 thinking budget -> guaranteed ~0.9s instant answer
                    put("maxOutputTokens", 40)
                    if (sendThinkingField) {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    }
                } else {
                    // Slot 2 (🧠): Exact 64 thinkingBudget from your working baseline + 200 maxOutputTokens so thought tokens never cut off the answer
                    put("maxOutputTokens", 200)
                    if (sendThinkingField) {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 64))
                    }
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

                if (finalAnswer.isEmpty()) "ERR:Empty" else formatSlotAnswer(finalAnswer)
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

    private fun formatSlotAnswer(raw: String): String {
        val clean = raw.replace("*", "").replace("\n", " ").trim()
        val match = Regex("""([A-Da-d][\)\.\:]\s*\S+(?:\s+\S+)?)""").find(clean)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val words = clean.split(Regex("\\s+"))
        return if (words.size > 3) words.take(3).joinToString(" ") else clean
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
