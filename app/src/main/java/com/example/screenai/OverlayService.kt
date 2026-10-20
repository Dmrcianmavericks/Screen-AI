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
    private var overlayOpacityPercent: Int = 70

    // Rotates cleanly across all 8 keys
    private val keyStepCounter = AtomicInteger(0)
    private val geminiModelCache = ConcurrentHashMap<String, String>()
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
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

        // Clean up any existing overlay/session if user restarted from MainActivity
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
        apiKeys = rawKeys.split(",", "\n", " ")
            .map { it.trim() }
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

        // Stagger startup model discovery by 80ms per key so 8 simultaneous GET calls don't choke startup
        for ((idx, k) in apiKeys.withIndex()) {
            networkPool.execute {
                if (idx > 0) {
                    try { Thread.sleep(idx * 80L) } catch (_: Exception) {}
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
        // Exact 0.60 scale from your working baseline: keeps math exponents, signs, and text 100% clear
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

        // Completely transparent background with your exact 0%-100% slider opacity
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
            // High-visibility neon green with strong black halo so it reads clearly on BOTH white and black screens
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

        // 45ms guarantees Android's screen compositor pushes a fresh frame without the overlay
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
        bitmap.compress(Bitmap.CompressFormat.JPEG, 62, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Picks the next N healthy (non-cooling-down) keys from your 8-key pool.
     */
    private fun pickHealthyKeys(desiredCount: Int, exclude: Set<String> = emptySet()): List<String> {
        val total = apiKeys.size
        if (total == 0) return emptyList()
        val now = System.currentTimeMillis()
        val start = keyStepCounter.getAndAdd(desiredCount)

        val healthy = mutableListOf<String>()
        val cooling = mutableListOf<String>()

        for (i in 0 until total) {
            val k = apiKeys[(start + i) % total]
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
     * - Fires 3 healthy keys simultaneously.
     * - If any key fails (e.g. 429), that worker thread immediately grabs a fresh backup key from your 8 keys!
     * - Unlocks the instant 2 keys agree (~1.2s), OR within 450ms after the 1st valid answer arrives so a slow 3rd key never causes "Err (Time)"!
     */
    private fun executeSimultaneousMajorityVote(base64Image: String): String {
        val total = apiKeys.size
        if (total == 1) {
            val singleRes = callGeminiFast(apiKeys[0], base64Image, isVerifier = false)
            return if (!singleRes.startsWith("ERR:")) singleRes else "Err (${singleRes.removePrefix("ERR:")})"
        }

        val workerCount = minOf(3, total)
        val initialKeys = pickHealthyKeys(workerCount)
        val usedKeys = Collections.synchronizedSet(HashSet<String>(initialKeys))

        val voteLock = Object()
        val validResponses = mutableListOf<String>()
        val voteCounts = HashMap<String, Int>()
        val majorityWinner = AtomicReference<String?>(null)
        val lastErr = AtomicReference("Time")
        val decided = AtomicBoolean(false)
        val firstAnswerLatch = CountDownLatch(1)
        val finalLatch = CountDownLatch(1)
        val remaining = AtomicInteger(workerCount)

        for ((idx, startKey) in initialKeys.withIndex()) {
            networkPool.execute {
                // 35ms stagger prevents 3 simultaneous Base64 uploads from colliding in the mobile socket buffer
                if (idx > 0) {
                    try { Thread.sleep(idx * 35L) } catch (_: Exception) {}
                }

                var res = callGeminiFast(startKey, base64Image, isVerifier = (idx == 2))

                // Self-healing failover: if this key failed (e.g. 429) and we have unused keys in our 8-key pool, immediately grab a spare key!
                if (res.startsWith("ERR:") && !decided.get() && usedKeys.size < total) {
                    val spareKey = synchronized(usedKeys) {
                        val candidate = pickHealthyKeys(1, usedKeys).firstOrNull()
                        if (candidate != null) usedKeys.add(candidate)
                        candidate
                    }
                    if (spareKey != null) {
                        res = callGeminiFast(spareKey, base64Image, isVerifier = (idx == 2))
                    }
                }

                if (!res.startsWith("ERR:")) {
                    val voteKey = normalizeVoteKey(res)
                    synchronized(voteLock) {
                        validResponses.add(res)
                        val newCount = (voteCounts[voteKey] ?: 0) + 1
                        voteCounts[voteKey] = newCount

                        // Instant 2-of-3 Majority Lock! Unlocks main thread at 0ms wait once 2 keys agree!
                        if (newCount >= 2 && decided.compareAndSet(false, true)) {
                            majorityWinner.set(res)
                            finalLatch.countDown()
                        }
                    }
                    firstAnswerLatch.countDown()
                } else {
                    lastErr.set(res.removePrefix("ERR:"))
                }

                if (remaining.decrementAndGet() == 0) {
                    synchronized(voteLock) {
                        if (majorityWinner.get() == null && validResponses.isNotEmpty()) {
                            majorityWinner.set(pickBestResponse(validResponses, voteCounts))
                        }
                    }
                    firstAnswerLatch.countDown()
                    finalLatch.countDown()
                }
            }
        }

        // Wait up to 3.8s for at least 1 key (or 2-key majority) to answer
        firstAnswerLatch.await(3800, TimeUnit.MILLISECONDS)

        // If 2 keys haven't locked a majority yet, give the other parallel keys up to 450ms to finish voting
        if (!decided.get()) {
            finalLatch.await(450, TimeUnit.MILLISECONDS)
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

            // Your exact working model list from the Picture-Perfect baseline
            val preferred = listOf(
                "gemini-2.5-flash",
                "gemini-3.6-flash",
                "gemini-3.8-flash",
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

    private fun callGeminiFast(key: String, base64Image: String, isVerifier: Boolean): String {
        val model = geminiModelCache[key] ?: "gemini-2.5-flash"
        val allowThinking = !noThinkingModels.contains(model)

        var res = sendGeminiHttp(key, model, base64Image, includeThinking = allowThinking, isVerifier = isVerifier)

        if (res == "ERR:400" && allowThinking) {
            noThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, includeThinking = false, isVerifier = isVerifier)
        } else if (res == "ERR:404") {
            // Exact working fallback from your Picture-Perfect baseline so it never 404s
            val nextModel = if (model == "gemini-2.5-flash") "gemini-3.5-flash-lite" else "gemini-2.5-flash"
            geminiModelCache[key] = nextModel
            res = sendGeminiHttp(key, nextModel, base64Image, includeThinking = false, isVerifier = isVerifier)
        }

        if (res.startsWith("ERR:")) {
            val code = res.removePrefix("ERR:")
            if (code == "429") {
                // Cool down this key for 22 seconds so the other 7 keys take over seamlessly
                keyCooldownUntil[key] = System.currentTimeMillis() + 22_000L
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
        includeThinking: Boolean,
        isVerifier: Boolean
    ): String {
        return try {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$encodedKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 2600
                readTimeout = 2800
                doOutput = true
            }

            val prompt = if (isVerifier) {
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

            // Exact generationConfig from your Picture-Perfect baseline (thinkingBudget: 64 + maxOutputTokens: 120)
            val genConfig = JSONObject().apply {
                put("maxOutputTokens", 120)
                if (includeThinking) {
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 64))
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
        } catch (e: Exception) {
            "ERR:Time"
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
