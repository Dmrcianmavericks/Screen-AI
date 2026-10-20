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
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.random.Random

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
        System.setProperty("http.maxConnections", "24")

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

        // Pre-discover the best unlocked Gemini model and warm up HTTPS connections on startup
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
        val clickStartMs = SystemClock.elapsedRealtime()
        // Hard 3.30-second (3300ms) ceiling from the exact moment you tap SCAN
        val hardDeadlineMs = clickStartMs + 3300L

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
                val reply = executeSimultaneousMajorityVote(base64Img, hardDeadlineMs)
                Handler(Looper.getMainLooper()).post {
                    answerText.text = reply
                    isScanning = false
                }
            }
        }, 45)
    }

    // Scales width to max 480px so 3 simultaneous uploads finish in ~120ms while keeping quiz text sharp
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val targetBitmap = if (bitmap.width > 480) {
            val scale = 480f / bitmap.width.toFloat()
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, 480, newH, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        targetBitmap.compress(Bitmap.CompressFormat.JPEG, 55, outputStream)
        if (targetBitmap !== bitmap) {
            targetBitmap.recycle()
        }
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * SIMULTANEOUS 3-KEY MAJORITY VOTING ENGINE (WITH STRICT 3.3s HARD CUTOFF):
     * - Fires 3 keys at the exact same millisecond (0ms delay).
     * - Instant 2-of-3 agreement returns immediately (~1.1s - 1.9s).
     * - If the 3.3s hard timer hits while only 1 key has answered (or 2 keys have different answers),
     *   it immediately stops waiting and returns a random valid answer from the ones already received!
     */
    private fun executeSimultaneousMajorityVote(base64Image: String, hardDeadlineMs: Long): String {
        val total = apiKeys.size
        val initialRemainingMs = (hardDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(500L)

        if (total == 1) {
            val singleRes = callProvider(apiKeys[0], base64Image, isVerifier = false, readTimeoutMs = initialRemainingMs.toInt())
            return if (!singleRes.startsWith("ERR:")) singleRes else "Err (${singleRes.removePrefix("ERR:")})"
        }
        if (total == 2) {
            val pairRes = fireThreeKeysForMajority(listOf(apiKeys[0], apiKeys[1]), base64Image, initialRemainingMs)
            return if (!pairRes.startsWith("ERR:")) pairRes else "Err (${pairRes.removePrefix("ERR:")})"
        }

        val offset = trioStepCounter.getAndAdd(3)
        val trio1 = listOf(
            apiKeys[offset % total],
            apiKeys[(offset + 1) % total],
            apiKeys[(offset + 2) % total]
        ).distinct()

        val firstTrioAnswer = fireThreeKeysForMajority(trio1, base64Image, initialRemainingMs)
        if (!firstTrioAnswer.startsWith("ERR:")) {
            return firstTrioAnswer
        }

        // Only try Trio 2 if Trio 1 failed quickly (e.g. instant 429) AND at least 1.2s remains before the 3.3s deadline
        val timeLeftForTrio2 = hardDeadlineMs - SystemClock.elapsedRealtime()
        if (total >= 4 && timeLeftForTrio2 >= 1200L) {
            val trio2 = listOf(
                apiKeys[(offset + 3) % total],
                apiKeys[(offset + 4) % total],
                apiKeys[(offset + 5) % total]
            ).distinct()
            val secondTrioAnswer = fireThreeKeysForMajority(trio2, base64Image, timeLeftForTrio2)
            if (!secondTrioAnswer.startsWith("ERR:")) {
                return secondTrioAnswer
            }
            return "Err (${secondTrioAnswer.removePrefix("ERR:")})"
        }

        return "Err (${firstTrioAnswer.removePrefix("ERR:")})"
    }

    private fun fireThreeKeysForMajority(
        keysToFire: List<String>,
        base64Image: String,
        maxWaitMs: Long
    ): String {
        val count = keysToFire.size
        val voteLock = Object()
        val validResponses = mutableListOf<String>()
        val voteCounts = HashMap<String, Int>()
        val majorityWinner = AtomicReference<String?>(null)
        val lastHttpErr = AtomicReference<String?>(null)
        val lastGenericErr = AtomicReference("Time")
        val decided = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val remaining = AtomicInteger(count)

        val perSocketReadTimeout = (maxWaitMs - 100L).toInt().coerceIn(1000, 3050)

        for ((idx, k) in keysToFire.withIndex()) {
            networkPool.execute {
                val res = callProvider(k, base64Image, isVerifier = (idx == 2), readTimeoutMs = perSocketReadTimeout)
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
                            majorityWinner.set(res)
                            latch.countDown()
                        }
                    }
                } else {
                    val code = res.removePrefix("ERR:")
                    if (code != "Time" && code != "Conn") {
                        lastHttpErr.set(code)
                    } else {
                        lastGenericErr.set(code)
                    }
                }

                if (remaining.decrementAndGet() == 0) {
                    synchronized(voteLock) {
                        // All 3 finished with no 2 agreeing: pick randomly from the valid answers received
                        if (majorityWinner.get() == null && validResponses.isNotEmpty()) {
                            majorityWinner.set(validResponses[Random.nextInt(validResponses.size)])
                        }
                    }
                    latch.countDown()
                }
            }
        }

        // Strict hard limit: wakes up immediately on 2-of-3 majority OR at the exact 3.3s deadline!
        latch.await(maxWaitMs, TimeUnit.MILLISECONDS)

        // If 2 keys agreed, return majorityWinner; otherwise if 1 or 2 keys answered before 3.3s, pick randomly from them!
        val finalChoice = majorityWinner.get() ?: synchronized(voteLock) {
            if (validResponses.isNotEmpty()) {
                validResponses[Random.nextInt(validResponses.size)]
            } else {
                null
            }
        }

        return finalChoice ?: "ERR:${lastHttpErr.get() ?: lastGenericErr.get()}"
    }

    // Matches "B) precise", "(B) precise", "Option B: precise", or "B" as the same OPT_B vote
    private fun normalizeVoteKey(answer: String): String {
        val cleaned = answer.trim().uppercase().replace("*", "")
        val prefixMatch = Regex("""^\(?([A-D])[\)\.\:\-\s]""").find(cleaned)
        if (prefixMatch != null) {
            return "OPT_${prefixMatch.groupValues[1]}"
        }
        val optionWordMatch = Regex("""\bOPTION\s+([A-D])\b""").find(cleaned)
        if (optionWordMatch != null) {
            return "OPT_${optionWordMatch.groupValues[1]}"
        }
        if (cleaned.length == 1 && cleaned[0] in 'A'..'D') {
            return "OPT_$cleaned"
        }
        return cleaned.replace(Regex("[^A-Z0-9]"), "").take(12)
    }

    private fun callProvider(key: String, base64Image: String, isVerifier: Boolean, readTimeoutMs: Int): String {
        return if (key.startsWith("gsk_")) {
            callGroqFast(key, base64Image, readTimeoutMs)
        } else {
            callGeminiFast(key, base64Image, isVerifier, readTimeoutMs)
        }
    }

    private fun callGroqFast(groqKey: String, base64Image: String, readTimeoutMs: Int): String {
        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $groqKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 1800
                readTimeout = readTimeoutMs
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
        } catch (e: SocketTimeoutException) {
            "ERR:Time"
        } catch (e: UnknownHostException) {
            "ERR:Net"
        } catch (e: Exception) {
            "ERR:Conn"
        }
    }

    private fun discoverGeminiModelOnStartup(key: String): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
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

            // Prioritizes active current Flash models (3.6 / 3.8) first so new AQ. keys never hit retired 2.5-flash
            val preferred = listOf(
                "gemini-3.6-flash",
                "gemini-3.8-flash",
                "gemini-2.5-flash"
            )
            for (p in preferred) {
                if (unlocked.contains(p)) return p
            }
            unlocked.firstOrNull { it.contains("flash") && !it.contains("lite") && !it.contains("tts") && !it.contains("image") }
        } catch (e: Exception) {
            null
        }
    }

    private fun callGeminiFast(key: String, base64Image: String, isVerifier: Boolean, readTimeoutMs: Int): String {
        val startMs = SystemClock.elapsedRealtime()
        val model = geminiModelCache[key] ?: "gemini-3.6-flash"
        val allowThinking = !noThinkingModels.contains(model)

        var res = sendGeminiHttp(key, model, base64Image, includeThinking = allowThinking, isVerifier = isVerifier, readTimeoutMs = readTimeoutMs)

        val elapsed = SystemClock.elapsedRealtime() - startMs
        val remainingMs = (readTimeoutMs - elapsed.toInt())

        // Only retry if the first request failed fast (e.g. HTTP 400/404/503) and at least 1100ms remains inside the 3.3s window
        if (remainingMs >= 1100) {
            if (res == "ERR:400" && allowThinking) {
                noThinkingModels.add(model)
                res = sendGeminiHttp(key, model, base64Image, includeThinking = false, isVerifier = isVerifier, readTimeoutMs = remainingMs)
            } else if (res == "ERR:404" || res == "ERR:503" || res == "ERR:403") {
                val fallbackModels = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash")
                val nextModel = fallbackModels.firstOrNull { it != model } ?: "gemini-3.8-flash"
                geminiModelCache[key] = nextModel
                res = sendGeminiHttp(key, nextModel, base64Image, includeThinking = true, isVerifier = isVerifier, readTimeoutMs = remainingMs)
            }
        }
        return res
    }

    private fun sendGeminiHttp(
        key: String,
        modelName: String,
        base64Image: String,
        includeThinking: Boolean,
        isVerifier: Boolean,
        readTimeoutMs: Int
    ): String {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 1800
                readTimeout = readTimeoutMs.coerceAtLeast(900)
                doOutput = true
            }

            val prompt = if (isVerifier) {
                "CRITICAL VERIFIER: Watch out for traps (ANTONYM vs SYNONYM, NOT/EXCEPT, grammar tense, or math signs). Solve internally, verify all options (A/B/C/D), and output ONLY the correct option letter and exact answer (example: 'B) precise'). Max 4 words."
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
                // 800 ensures internal thinking tokens never truncate the visible 4-word answer
                put("maxOutputTokens", 800)
                if (includeThinking) {
                    if (modelName.startsWith("gemini-2.5")) {
                        val budget = if (isVerifier) 96 else 64
                        put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
                    } else {
                        // Official Gemini 3.6 / 3.8 REST spec uses lowercase "low" for fast reasoning
                        put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
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

                if (finalAnswer.isEmpty()) "ERR:Empty" else finalAnswer.replace("\n", " ").replace("*", "")
            } else {
                conn.errorStream?.use { it.readBytes() }
                "ERR:$code"
            }
        } catch (e: SocketTimeoutException) {
            "ERR:Time"
        } catch (e: UnknownHostException) {
            "ERR:Net"
        } catch (e: Exception) {
            "ERR:Conn"
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
