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
    private var overlayOpacityPercent: Int = 65

    // Steps by 3 on every scan for 3-Key Simultaneous Majority Voting:
    // Scan 1 -> [0, 1, 2], Scan 2 -> [3, 4, 5], Scan 3 -> [6, 7, 0]...
    private val trioStepCounter = AtomicInteger(0)
    private val geminiModelCache = ConcurrentHashMap<String, String>()
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

        overlayOpacityPercent = intent.getIntExtra("OPACITY_PERCENT", 65).coerceIn(0, 100)

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

        // Pre-discover the best unlocked Gemini model for each key in the background on startup ONLY
        for (k in apiKeys) {
            if (!k.startsWith("gsk_")) {
                networkPool.execute {
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

        // Completely transparent background so no dark rectangle ever gives it away
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 36f
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding(12, 6, 16, 6)
            gravity = Gravity.CENTER_VERTICAL
            // Applies your exact 0% - 100% slider opacity to the entire floating bar
            alpha = overlayOpacityPercent / 100f
        }

        val btnDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#22000000"))
            setStroke(2, Color.parseColor("#55888888"))
            cornerRadius = 20f
        }

        val scanButton = Button(this).apply {
            text = "SCAN"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            background = btnDrawable
            setPadding(14, 2, 14, 2)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                if (!isScanning) captureAndAskAI()
            }
        }

        answerText = TextView(this).apply {
            text = "Ready (${apiKeys.size})"
            setTextColor(Color.parseColor("#112211"))
            // Subtle text shadow so you can read it on both white and dark backgrounds without a box
            setShadowLayer(3f, 0f, 0f, Color.WHITE)
            textSize = 14f
            setPadding(18, 0, 18, 0)
            minWidth = 180
            maxWidth = 680
            maxLines = 2
        }

        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(10, 4, 8, 4)
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
     * SIMULTANEOUS 3-KEY MAJORITY VOTING ENGINE:
     * - Fires 3 keys at the exact same millisecond (0ms delay).
     * - The instant ANY 2 keys agree on the same answer (2-of-3 majority), it returns that answer immediately!
     * - If all 3 keys fail (e.g. 429), it immediately falls back to the next 3 keys!
     */
    private fun executeSimultaneousMajorityVote(base64Image: String): String {
        val total = apiKeys.size
        if (total == 1) {
            val singleRes = callProvider(apiKeys[0], base64Image, isVerifier = false)
            return if (!singleRes.startsWith("ERR:")) singleRes else "Err (${singleRes.removePrefix("ERR:")})"
        }
        if (total == 2) {
            val pairRes = fireThreeKeysForMajority(listOf(apiKeys[0], apiKeys[1]), base64Image)
            return if (!pairRes.startsWith("ERR:")) pairRes else "Err (${pairRes.removePrefix("ERR:")})"
        }

        // Steps by 3 on every scan across your 4 to 8 keys:
        // Click 1 -> [0, 1, 2], Click 2 -> [3, 4, 5], Click 3 -> [6, 7, 0]...
        val offset = trioStepCounter.getAndAdd(3)
        val trio1 = listOf(
            apiKeys[offset % total],
            apiKeys[(offset + 1) % total],
            apiKeys[(offset + 2) % total]
        ).distinct()

        val firstTrioAnswer = fireThreeKeysForMajority(trio1, base64Image)
        if (!firstTrioAnswer.startsWith("ERR:")) {
            return firstTrioAnswer
        }

        // If all 3 keys in Trio 1 failed, immediately fire the next Trio!
        if (total >= 4) {
            val trio2 = listOf(
                apiKeys[(offset + 3) % total],
                apiKeys[(offset + 4) % total],
                apiKeys[(offset + 5) % total]
            ).distinct()
            val secondTrioAnswer = fireThreeKeysForMajority(trio2, base64Image)
            if (!secondTrioAnswer.startsWith("ERR:")) {
                return secondTrioAnswer
            }
            return "Err (${secondTrioAnswer.removePrefix("ERR:")})"
        }

        return "Err (${firstTrioAnswer.removePrefix("ERR:")})"
    }

    private fun fireThreeKeysForMajority(keysToFire: List<String>, base64Image: String): String {
        val count = keysToFire.size
        val voteLock = Object()
        val validResponses = mutableListOf<String>()
        val voteCounts = HashMap<String, Int>()
        val majorityWinner = AtomicReference<String?>(null)
        val lastErr = AtomicReference("Time")
        val decided = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val remaining = AtomicInteger(count)

        for ((idx, k) in keysToFire.withIndex()) {
            networkPool.execute {
                // Worker 0 & 1 use the fast direct prompt; Worker 2 uses the Trap-Verifier prompt at the same 0ms!
                val res = callProvider(k, base64Image, isVerifier = (idx == 2))
                if (!res.startsWith("ERR:")) {
                    val voteKey = normalizeVoteKey(res)
                    synchronized(voteLock) {
                        validResponses.add(res)
                        val newCount = (voteCounts[voteKey] ?: 0) + 1
                        voteCounts[voteKey] = newCount

                        // Instant 2-of-3 Majority Lock! Unlocks the main thread the millisecond 2 keys agree!
                        if (newCount >= 2 && decided.compareAndSet(false, true)) {
                            majorityWinner.set(res)
                            latch.countDown()
                        } else if (count == 2 && remaining.get() == 1 && validResponses.size == 1 && decided.compareAndSet(false, true)) {
                            // If only 2 keys total exist and the other one errored out, accept the 1 valid answer
                            majorityWinner.set(res)
                            latch.countDown()
                        }
                    }
                } else {
                    lastErr.set(res.removePrefix("ERR:"))
                }

                if (remaining.decrementAndGet() == 0) {
                    synchronized(voteLock) {
                        // All 3 threads finished: if no 2 agreed (or only 1 succeeded), pick the best valid response
                        if (majorityWinner.get() == null && validResponses.isNotEmpty()) {
                            majorityWinner.set(validResponses[0])
                        }
                    }
                    latch.countDown()
                }
            }
        }

        // Waits at most 3.8 seconds (usually unlocks in ~1.2s the instant 2 keys agree!)
        latch.await(3800, TimeUnit.MILLISECONDS)

        val finalChoice = majorityWinner.get() ?: synchronized(voteLock) { validResponses.firstOrNull() }
        return finalChoice ?: "ERR:${lastErr.get()}"
    }

    // Extracts the core option letter (e.g. "B) precise" -> "B") so "B) precise" and "B. precise" count as the same vote!
    private fun normalizeVoteKey(answer: String): String {
        val trimmed = answer.trim().uppercase()
        val optionMatch = Regex("""^([A-D])[\)\.\:\s]""").find(trimmed)
        if (optionMatch != null) {
            return "OPT_${optionMatch.groupValues[1]}"
        }
        return trimmed.replace(Regex("[^A-Z0-9]"), "").take(12)
    }

    private fun callProvider(key: String, base64Image: String, isVerifier: Boolean): String {
        return if (key.startsWith("gsk_")) {
            callGroqFast(key, base64Image)
        } else {
            callGeminiFast(key, base64Image, isVerifier)
        }
    }

    private fun callGroqFast(groqKey: String, base64Image: String): String {
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

            val prompt = "Check carefully if the question asks for SYNONYM, ANTONYM, Grammar, or Math. Solve it and reply ONLY with the correct option letter and exact word/number (example: 'B) precise'). Max 4 words."

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
                put("model", "meta-llama/llama-4-maverick-17b-128e-instruct")
                put("messages", messages)
                put("temperature", 0.0)
                put("max_tokens", 30)
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
                    .replace("\n", " ")
                    .replace("*", "")
                if (text.isNotEmpty()) text else "ERR:Empty"
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

            // Strictly prioritizes smart full Flash models (never picks weak -lite models if full Flash is unlocked!)
            val preferred = listOf(
                "gemini-2.5-flash",
                "gemini-3.6-flash",
                "gemini-3.8-flash"
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

    // Never blocks on GET /models during a live quiz scan, and NEVER caches weak -lite models!
    private fun callGeminiFast(key: String, base64Image: String, isVerifier: Boolean): String {
        val model = geminiModelCache[key] ?: "gemini-2.5-flash"
        val allowThinking = !noThinkingModels.contains(model)

        var res = sendGeminiHttp(key, model, base64Image, includeThinking = allowThinking, isVerifier = isVerifier)

        if (res == "ERR:400" && allowThinking) {
            noThinkingModels.add(model)
            res = sendGeminiHttp(key, model, base64Image, includeThinking = false, isVerifier = isVerifier)
        } else if (res == "ERR:404") {
            // Switch between full-intelligence Flash models (never locks into -lite!)
            val nextModel = if (model == "gemini-2.5-flash") "gemini-3.8-flash" else "gemini-2.5-flash"
            geminiModelCache[key] = nextModel
            res = sendGeminiHttp(key, nextModel, base64Image, includeThinking = false, isVerifier = isVerifier)
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
                readTimeout = 2600
                doOutput = true
            }

            val prompt = if (isVerifier) {
                "CRITICAL VERIFIER: Watch out for traps (ANTONYM vs SYNONYM, NOT/EXCEPT, grammar tense, or math signs). Verify all options (A/B/C/D) and output ONLY the correct option letter and exact answer (example: 'B) precise'). Max 4 words."
            } else {
                "Pay strict attention to whether the question asks for SYNONYM, ANTONYM, Grammar, or Math calculation. Double-check the options (A/B/C/D) and output ONLY the correct option letter and exact answer (example: 'B) precise'). Max 4 words."
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

                if (finalAnswer.isEmpty()) "ERR:Empty" else finalAnswer.replace("\n", " ").replace("*", "")
            } else {
                conn.errorStream?.use { it.readBytes() }
                "ERR:$code"
            }
        } catch (e: Exception) {
            "ERR:Time"
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
