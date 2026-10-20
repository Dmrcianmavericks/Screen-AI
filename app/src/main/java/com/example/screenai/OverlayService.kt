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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    // Thread-safe active key pool: dead 401/403 keys are automatically removed so they never break a scan
    private val activeKeys = CopyOnWriteArrayList<String>()
    private var overlayOpacityPercent: Int = 70

    private val keyStepCounter = AtomicInteger(0)
    private val geminiModelCache = ConcurrentHashMap<String, String>()
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
    private val noThinkingModels = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val vertexEndpointKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

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

        overlayOpacityPercent = intent.getIntExtra("OPACITY_PERCENT", 70).coerceIn(5, 100)

        val rawKeys = intent.getStringExtra("API_KEY") ?: ""
        val parsedKeys = rawKeys.split(",", "\n", " ")
            .map { it.trim().replace("\"", "").replace("'", "") }
            .filter { it.length > 12 && !it.startsWith("gsk_") }
            .distinct()

        activeKeys.clear()
        activeKeys.addAll(parsedKeys)

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

        // Validate keys and cache their fastest unlocked model on startup
        for ((idx, k) in parsedKeys.withIndex()) {
            networkPool.execute {
                if (idx > 0) {
                    try { Thread.sleep(idx * 60L) } catch (_: Exception) {}
                }
                validateAndDiscoverKeyOnStartup(k)
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
                .setContentText("3-Key Majority Voting Mode")
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
        // 0.56 scale keeps math symbols (+, -, ÷, exponents) sharp while reducing 3x simultaneous upload time
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
            text = "Ready (${activeKeys.size} keys)"
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
        if (activeKeys.isEmpty()) {
            answerText.text = "No Valid Keys!"
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
                val reply = executeSimultaneousMajorityVote(base64Img)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 45)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun pickHealthyKeys(desiredCount: Int, exclude: Set<String> = emptySet()): List<String> {
        val snapshot = activeKeys.toList()
        val total = snapshot.size
        if (total == 0) return emptyList()

        val now = System.currentTimeMillis()
        val start = keyStepCounter.getAndAdd(desiredCount)

        val healthy = mutableListOf<String>()
        val cooling = mutableListOf<String>()

        for (i in 0 until total) {
            val k = snapshot[(start + i) % total]
            if (exclude.contains(k)) continue
            if (now >= (keyCooldownUntil[k] ?: 0L)) {
                healthy.add(k)
            } else {
                cooling.add(k)
            }
        }
        return (healthy + cooling).take(desiredCount)
    }

    /**
     * SIMULTANEOUS 3-KEY MAJORITY VOTING ENGINE:
     * - Worker 0: Fast Sprinter (thinkingBudget = 0, answers in ~1.0s)
     * - Worker 1: Math & Logic Reasoner (thinkingBudget = 48, answers in ~1.4s)
     * - Worker 2: Trap & Calculation Verifier (thinkingBudget = 48, answers in ~1.5s)
     * - Unlocks immediately when ANY 2 keys agree (~1.3s), or returns the best valid answer without timing out!
     */
    private fun executeSimultaneousMajorityVote(base64Image: String): String {
        val totalAvailable = activeKeys.size
        if (totalAvailable == 0) return "Err (No valid key)"
        if (totalAvailable == 1) {
            val singleRes = callGeminiFast(activeKeys[0], base64Image, workerRole = 1)
            return if (!singleRes.startsWith("ERR:")) singleRes else "Err (${singleRes.removePrefix("ERR:")})"
        }

        val workerCount = minOf(3, totalAvailable)
        val initialKeys = pickHealthyKeys(workerCount)
        val usedKeys = Collections.synchronizedSet(HashSet<String>(initialKeys))

        val voteLock = Object()
        val validResponses = mutableListOf<String>()
        val voteCounts = HashMap<String, Int>()
        val majorityWinner = AtomicReference<String?>(null)
        val lastErr = AtomicReference("Time")
        val decided = AtomicBoolean(false)
        val firstAnswerLatch = CountDownLatch(1)
        val majorityLatch = CountDownLatch(1)
        val remaining = AtomicInteger(workerCount)

        for ((idx, startKey) in initialKeys.withIndex()) {
            networkPool.execute {
                // 40ms stagger prevents 3 simultaneous uploads from choking the mobile socket
                if (idx > 0) {
                    try { Thread.sleep(idx * 40L) } catch (_: Exception) {}
                }

                var res = callGeminiFast(startKey, base64Image, workerRole = idx)

                // If this key failed quickly (e.g. 401/403/429) and we have spare keys in our 8-key pool, immediately grab one!
                if (res.startsWith("ERR:") && !decided.get() && usedKeys.size < activeKeys.size) {
                    val spareKey = synchronized(usedKeys) {
                        val candidate = pickHealthyKeys(1, usedKeys).firstOrNull()
                        if (candidate != null) usedKeys.add(candidate)
                        candidate
                    }
                    if (spareKey != null && !decided.get()) {
                        res = callGeminiFast(spareKey, base64Image, workerRole = idx)
                    }
                }

                if (!res.startsWith("ERR:")) {
                    val voteKey = normalizeVoteKey(res)
                    synchronized(voteLock) {
                        validResponses.add(res)
                        val newCount = (voteCounts[voteKey] ?: 0) + 1
                        voteCounts[voteKey] = newCount

                        // Instant 2-of-3 Majority Lock! Unlocks main thread the exact millisecond 2 keys agree!
                        if (newCount >= 2 && decided.compareAndSet(false, true)) {
                            majorityWinner.set(res)
                            majorityLatch.countDown()
                        }
                    }
                    firstAnswerLatch.countDown()
                } else {
                    val errCode = res.removePrefix("ERR:")
                    // Only overwrite lastErr if we don't already have a specific HTTP code (like 429/401)
                    if (errCode != "Time" || lastErr.get() == "Time") {
                        lastErr.set(errCode)
                    }
                }

                if (remaining.decrementAndGet() == 0) {
                    synchronized(voteLock) {
                        if (majorityWinner.get() == null && validResponses.isNotEmpty()) {
                            majorityWinner.set(pickBestResponse(validResponses, voteCounts))
                        }
                    }
                    firstAnswerLatch.countDown()
                    majorityLatch.countDown()
                }
            }
        }

        // Wait up to 5.5s for the 1st valid answer (or until all workers finish)
        firstAnswerLatch.await(5500, TimeUnit.MILLISECONDS)

        // Once the 1st answer is in, give the other 2 parallel reasoner keys up to 1000ms to lock a 2-of-3 majority
        if (!decided.get()) {
            majorityLatch.await(1000, TimeUnit.MILLISECONDS)
        }

        val finalChoice = majorityWinner.get() ?: synchronized(voteLock) {
            if (validResponses.isNotEmpty()) pickBestResponse(validResponses, voteCounts) else null
        }

        return finalChoice ?: "Err (${lastErr.get()})"
    }

    private fun pickBestResponse(responses: List<String>, counts: Map<String, Int>): String {
        var best = responses[0]
        var maxVotes = 0
        for (r in responses) {
            val v = counts[normalizeVoteKey(r)] ?: 0
            if (v > maxVotes) {
                maxVotes = v
                best = r
            }
        }
        return best
    }

    private fun normalizeVoteKey(answer: String): String {
        val trimmed = answer.trim().uppercase()
        val optionMatch = Regex("""^([A-D])[\)\.\:\s]""").find(trimmed)
        if (optionMatch != null) {
            return "OPT_${optionMatch.groupValues[1]}"
        }
        return trimmed.replace(Regex("[^A-Z0-9]"), "").take(12)
    }

    /**
     * Checks each key on startup:
     * 1. Finds its best unlocked Flash model on generativelanguage.googleapis.com.
     * 2. If a key returns 401/403, removes it from activeKeys so it NEVER causes Err (401) during your quiz!
     */
    private fun validateAndDiscoverKeyOnStartup(key: String) {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = 3500
                readTimeout = 3500
            }
            val code = conn.responseCode
            if (code == 200) {
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

                val preferred = listOf(
                    "gemini-2.5-flash",
                    "gemini-3.6-flash",
                    "gemini-3.7-flash",
                    "gemini-3.8-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-3.5-flash-lite"
                )
                for (p in preferred) {
                    if (unlocked.contains(p)) {
                        geminiModelCache[key] = p
                        return
                    }
                }
                unlocked.firstOrNull { it.contains("flash") && !it.contains("tts") && !it.contains("image") }
                    ?.let { geminiModelCache[key] = it }
            } else if (code == 401 || code == 403) {
                conn.errorStream?.use { it.readBytes() }
                // Key is rejected by generativelanguage.googleapis.com; remove it if other keys exist
                if (activeKeys.size > 1) {
                    activeKeys.remove(key)
                    Handler(Looper.getMainLooper()).post {
                        if (!isScanning && ::answerText.isInitialized) {
                            answerText.text = "Ready (${activeKeys.size} ok)"
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun callGeminiFast(key: String, base64Image: String, workerRole: Int): String {
        val model = geminiModelCache[key] ?: "gemini-2.5-flash"
        val allowThinking = !noThinkingModels.contains(model) && (workerRole > 0)

        var res = sendGeminiHttp(key, model, base64Image, includeThinking = allowThinking, workerRole = workerRole)

        if (res == "ERR:400" && allowThinking) {
            noThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, includeThinking = false, workerRole = workerRole)
        } else if (res == "ERR:404") {
            val nextModel = if (model == "gemini-2.5-flash") "gemini-3.5-flash-lite" else "gemini-2.5-flash"
            geminiModelCache[key] = nextModel
            res = sendGeminiHttp(key, nextModel, base64Image, includeThinking = false, workerRole = workerRole)
        } else if (res == "ERR:401" && !vertexEndpointKeys.contains(key)) {
            // If an AQ. key returned 401 on generativelanguage, try Vertex Express endpoint once
            vertexEndpointKeys.add(key)
            res = sendGeminiHttp(key, "gemini-2.5-flash", base64Image, includeThinking = false, workerRole = workerRole)
        }

        if (res.startsWith("ERR:")) {
            val code = res.removePrefix("ERR:")
            if (code == "429") {
                keyCooldownUntil[key] = System.currentTimeMillis() + 22_000L
            } else if (code == "401" || code == "403") {
                // Permanently remove broken 401/403 key from active rotation so it never causes an error again!
                keyCooldownUntil[key] = System.currentTimeMillis() + 600_000L
                if (activeKeys.size > 1) {
                    activeKeys.remove(key)
                }
            }
        }
        return res
    }

    private fun sendGeminiHttp(
        key: String,
        modelName: String,
        base64Image: String,
        includeThinking: Boolean,
        workerRole: Int
    ): String {
        return try {
            val endpointUrl = if (vertexEndpointKeys.contains(key)) {
                "https://aiplatform.googleapis.com/v1/publishers/google/models/$modelName:generateContent"
            } else {
                "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            }

            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                // Single clean authentication header (no duplicate ?key= query param)
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 3000
                // 5200ms readTimeout prevents premature "Err (Time)" cutoffs when 3 images upload simultaneously
                readTimeout = 5200
                doOutput = true
            }

            val prompt = if (workerRole == 2) {
                "CRITICAL MATH & LOGIC VERIFIER: Solve any math calculation step-by-step internally, and check for ANTONYM vs SYNONYM or NOT/EXCEPT traps. Verify all options (A/B/C/D) and output ONLY the correct option letter and exact answer (example: 'B) precise' or 'C) 24'). NO explanation. Max 4 words."
            } else {
                "Pay strict attention to whether the question asks for SYNONYM, ANTONYM, Grammar, or Math calculation. Double-check the options (A/B/C/D) and output ONLY the correct option letter and exact answer (example: 'B) precise' or 'C) 24'). NO explanation. Max 4 words."
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
                put("maxOutputTokens", 120)
                if (includeThinking) {
                    // Worker 1 & 2 use 48 thinking tokens for math/logic accuracy
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 48))
                } else if (!noThinkingModels.contains(modelName) && modelName.contains("2.5")) {
                    // Worker 0 explicitly sets thinkingBudget = 0 so it sprints in ~1.0s instead of defaulting to 8192 dynamic thinking!
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
