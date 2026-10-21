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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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
    // Strictly steps by 2: Scan 1 -> [0, 1] (Key 1 & 2), Scan 2 -> [2, 3] (Key 3 & 4), Scan 3 -> [0, 1] (Key 1 & 2)
    private val pairStepCounter = AtomicInteger(0)
    private val geminiModelCache = ConcurrentHashMap<String, String>()
    private val groqModelCache = ConcurrentHashMap<String, String>()
    private val noThinkingModels = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // Pre-allocated thread pool so Android never wastes time creating new OS threads on each click
    private val networkPool = Executors.newCachedThreadPool()

    @Volatile private var isScanning = false
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "16")

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

        // Pre-discover the best unlocked model for BOTH Gemini keys AND Groq (gsk_) keys on startup!
        for (k in apiKeys) {
            networkPool.execute {
                if (k.startsWith("gsk_")) {
                    discoverGroqModelOnStartup(k)?.let { groqModelCache[k] = it }
                } else {
                    discoverGeminiModelOnStartup(k)?.let { geminiModelCache[k] = it }
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
                .setContentText("Simultaneous Pair Mode: 1/2 <-> 3/4")
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
        // 68% scale keeps math signs (+, -, ÷, exponents) and punctuation sharp so AI doesn't misread after 10+ scans
        val width = (metrics.widthPixels * 0.68).toInt()
        val height = (metrics.heightPixels * 0.68).toInt()
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

                    // Crops out top 12% (URL bar) and bottom 18% (Nav bar) so only the quiz card is captured
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
        overlayView.invalidate()

        // 50ms guarantees Android's screen compositor pushes a fresh frame of the new question
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
                val reply = executeSimultaneousPair(base64Img)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 50)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * SIMULTANEOUS 2-KEY PAIR ENGINE (Key 1+2 -> Key 3+4 -> Key 1+2):
     * Fires BOTH keys in the active pair at the exact same millisecond (0ms delay).
     * Whichever key responds first immediately returns the answer!
     */
    private fun executeSimultaneousPair(base64Image: String): String {
        val total = apiKeys.size
        if (total == 1) {
            val singleRes = callProvider(apiKeys[0], base64Image)
            return if (!singleRes.startsWith("ERR:")) singleRes else "Err (${singleRes.removePrefix("ERR:")})"
        }

        val offset = pairStepCounter.getAndAdd(2)
        val keyA = apiKeys[offset % total]
        val keyB = apiKeys[(offset + 1) % total]

        val firstPairAnswer = fireTwoKeysSimultaneously(keyA, keyB, base64Image)
        if (!firstPairAnswer.startsWith("ERR:")) {
            return firstPairAnswer
        }

        if (total >= 3) {
            val keyC = apiKeys[(offset + 2) % total]
            val keyD = apiKeys[(offset + 3) % total]
            val secondPairAnswer = if (keyC != keyD) {
                fireTwoKeysSimultaneously(keyC, keyD, base64Image)
            } else {
                callProvider(keyC, base64Image)
            }
            if (!secondPairAnswer.startsWith("ERR:")) {
                return secondPairAnswer
            }
            return "Err (${secondPairAnswer.removePrefix("ERR:")})"
        }

        return "Err (${firstPairAnswer.removePrefix("ERR:")})"
    }

    private fun fireTwoKeysSimultaneously(keyA: String, keyB: String, base64Image: String): String {
        val winner = AtomicReference<String?>(null)
        val lastErr = AtomicReference("Time")
        val solved = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val remaining = AtomicInteger(2)

        val keysToFire = listOf(keyA, keyB)
        for (k in keysToFire) {
            networkPool.execute {
                val res = callProvider(k, base64Image)
                if (!res.startsWith("ERR:")) {
                    if (solved.compareAndSet(false, true)) {
                        winner.set(res)
                        latch.countDown()
                    }
                } else {
                    lastErr.set(res.removePrefix("ERR:"))
                }
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown()
                }
            }
        }

        latch.await(3800, TimeUnit.MILLISECONDS)
        return winner.get() ?: "ERR:${lastErr.get()}"
    }

    private fun callProvider(key: String, base64Image: String): String {
        return if (key.startsWith("gsk_")) {
            callGroqFast(key, base64Image)
        } else {
            callGeminiFast(key, base64Image)
        }
    }

    // Auto-discovers the exact Vision model enabled on your Groq account so it NEVER throws 404!
    private fun discoverGroqModelOnStartup(groqKey: String): String? {
        return try {
            val url = URL("https://api.groq.com/openai/v1/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $groqKey")
                connectTimeout = 2500
                readTimeout = 2500
            }
            if (conn.responseCode != 200) return null

            val responseText = conn.inputStream.use { it.bufferedReader().readText() }
            val data = JSONObject(responseText).getJSONArray("data")
            val available = mutableListOf<String>()
            for (i in 0 until data.length()) {
                available.add(data.getJSONObject(i).getString("id"))
            }

            // Priority list of Groq multimodal vision models
            val preferredGroqVision = listOf(
                "meta-llama/llama-4-maverick-17b-128e-instruct",
                "meta-llama/llama-4-scout-17b-16e-instruct",
                "llama-3.2-90b-vision-preview",
                "llama-3.2-11b-vision-preview",
                "llama-3.2-90b-vision",
                "llama-3.2-11b-vision"
            )
            for (pref in preferredGroqVision) {
                if (available.contains(pref)) return pref
            }
            available.firstOrNull { it.contains("vision") || it.contains("llama-4") }
        } catch (e: Exception) {
            null
        }
    }

    private fun callGroqFast(groqKey: String, base64Image: String): String {
        val primaryModel = groqModelCache[groqKey] ?: "meta-llama/llama-4-scout-17b-16e-instruct"
        val modelsToTry = listOf(
            primaryModel,
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "llama-3.2-90b-vision-preview",
            "llama-3.2-11b-vision-preview"
        ).distinct()

        var lastCode = "404"
        for (model in modelsToTry) {
            val res = sendGroqHttp(groqKey, model, base64Image)
            if (!res.startsWith("ERR:")) {
                groqModelCache[groqKey] = model // Lock in the working Groq vision model!
                return res
            }
            lastCode = res.removePrefix("ERR:")
            // Only keep trying next model if it was 404/400 (model name issue); if 401/429 stop immediately
            if (lastCode != "404" && lastCode != "400") break
        }
        return "ERR:$lastCode"
    }

    private fun sendGroqHttp(groqKey: String, modelName: String, base64Image: String): String {
        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $groqKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 2600
                readTimeout = 2600
                doOutput = true
            }

            val prompt = "Check carefully if the question asks for SYNONYM, ANTONYM, Grammar, or Math. Solve it accurately and output ONLY the correct option letter and exact word/number (example: 'B) precise'). NO explanation. Max 4 words."

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
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.0)
                put("max_tokens", 25)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code == 200) {
                val responseText = conn.inputStream.use { it.bufferedReader().readText() }
                val res = JSONObject(responseText)
                val text = res.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                if (text.isNotEmpty()) cleanShortAnswer(text) else "ERR:Empty"
            } else {
                conn.errorStream?.use { it.readBytes() }
                "ERR:$code"
            }
        } catch (e: Exception) {
            "ERR:Time"
        }
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

            // Strictly prioritize full Flash models (smartest at Math & English) over "-lite" models!
            val preferred = listOf(
                "gemini-2.5-flash",
                "gemini-3.6-flash",
                "gemini-3.8-flash",
                "gemini-3.5-flash",
                "gemini-2.0-flash",
                "gemini-2.5-flash-lite",
                "gemini-3.5-flash-lite"
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
        val allowThinking = !noThinkingModels.contains(model)

        var res = sendGeminiHttp(key, model, base64Image, includeThinking = allowThinking)

        if (res == "ERR:400" && allowThinking) {
            noThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, includeThinking = false)
        } else if (res == "ERR:404") {
            // Fall back to full Flash model first so intelligence never drops after 10-12 scans!
            val nextModel = if (model == "gemini-2.5-flash") "gemini-2.0-flash" else "gemini-2.5-flash"
            geminiModelCache[key] = nextModel
            res = sendGeminiHttp(key, nextModel, base64Image, includeThinking = false)
        }
        return res
    }

    private fun sendGeminiHttp(key: String, modelName: String, base64Image: String, includeThinking: Boolean): String {
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$encodedKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 2600
                readTimeout = 2600
                doOutput = true
            }

            val prompt = "Read the question and options (A, B, C, D) carefully. Verify if it asks for SYNONYM, ANTONYM, Grammar, or Math. Solve it and output ONLY the correct option letter and exact answer (example: 'B) precise'). NO explanation."

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
                put("temperature", 0.0) // Zero randomness so it never guesses wrong after 10+ questions!
                put("maxOutputTokens", 180) // 128 budget for internal thinking + 52 for final text
                if (includeThinking) {
                    // 128 thinking tokens (~0.25s) prevents silly math and antonym/synonym mistakes
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 128))
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

                if (finalAnswer.isEmpty()) "ERR:Empty" else cleanShortAnswer(finalAnswer)
            } else {
                conn.errorStream?.use { it.readBytes() }
                "ERR:$code"
            }
        } catch (e: Exception) {
            "ERR:Time"
        }
    }

    // Strips any accidental AI explanation and extracts just the option + answer (e.g. "B) precise")
    private fun cleanShortAnswer(raw: String): String {
        val singleLine = raw.replace("*", "").replace("\n", " ").trim()
        // If AI wrote a long explanation containing "B) precise", extract just the option and answer
        val words = singleLine.split(Regex("\\s+"))
        return if (words.size > 6) {
            words.take(5).joinToString(" ")
        } else {
            singleLine
        }
    }

    private fun cleanupAndStop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        networkPool.shutdownNow()
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
