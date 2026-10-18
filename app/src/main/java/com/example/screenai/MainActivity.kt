package com.example.screenai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var keyInput1: EditText
    private lateinit var keyInput2: EditText
    private lateinit var keyInput3: EditText
    private lateinit var keyInput4: EditText
    private lateinit var projectionManager: MediaProjectionManager

    // Your 4 exact keys pre-loaded as defaults
    private val defaultKey1 = "AIzaSyDhqzh0eUNaB__ZASLqLGOCyZllOApgt5c"
    private val defaultKey2 = "AQ.Ab8RN6KFMF8d5RSRL8nhUxHoKW0x1peVjzcK9EKXvm_UHnu6Fw"
    private val defaultKey3 = "AQ.Ab8RN6JlOf5UBbxOy0qWFy8mhN1tIC7he5M-Bi15rf18s4-0XA"
    private val defaultKey4 = "AQ.Ab8RN6IJ29Ou1fXy1sfWRFMVXXdwQJGISvauvOnNWGfztlBb4g"

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val combinedKeys = listOf(
                keyInput1.text.toString().trim(),
                keyInput2.text.toString().trim(),
                keyInput3.text.toString().trim(),
                keyInput4.text.toString().trim()
            ).filter { it.length > 10 }.joinToString(",")

            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("API_KEY", combinedKeys)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        } else {
            Toast.makeText(this, "Screen permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val prefs = getSharedPreferences("ScreenAIPrefs", Context.MODE_PRIVATE)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 220, 60, 80)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "ScreenAI (4-Key Dual Engine)"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "Supports both AIzaSy (AI Studio) and AQ. (Vertex Express) keys automatically."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 30)
        }

        val saved1 = prefs.getString("KEY_1", "").takeIf { !it.isNullOrBlank() } ?: defaultKey1
        val saved2 = prefs.getString("KEY_2", "").takeIf { !it.isNullOrBlank() } ?: defaultKey2
        val saved3 = prefs.getString("KEY_3", "").takeIf { !it.isNullOrBlank() } ?: defaultKey3
        val saved4 = prefs.getString("KEY_4", "").takeIf { !it.isNullOrBlank() } ?: defaultKey4

        keyInput1 = createKeyBox("Key 1 (AIza or AQ.)", saved1)
        keyInput2 = createKeyBox("Key 2 (AIza or AQ.)", saved2)
        keyInput3 = createKeyBox("Key 3 (AIza or AQ.)", saved3)
        keyInput4 = createKeyBox("Key 4 (AIza or AQ.)", saved4)

        val startBtn = Button(this).apply {
            text = "Start AI Overlay"
            setOnClickListener {
                val k1 = keyInput1.text.toString().trim()
                val k2 = keyInput2.text.toString().trim()
                val k3 = keyInput3.text.toString().trim()
                val k4 = keyInput4.text.toString().trim()

                if (k1.isEmpty() && k2.isEmpty() && k3.isEmpty() && k4.isEmpty()) {
                    Toast.makeText(context, "Enter at least 1 key!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString("KEY_1", k1)
                    .putString("KEY_2", k2)
                    .putString("KEY_3", k3)
                    .putString("KEY_4", k4)
                    .apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(context, "Enable 'Display over other apps', then tap Start", Toast.LENGTH_LONG).show()
                } else {
                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "Stop Overlay"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(context, "Overlay stopped", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(keyInput1)
        layout.addView(keyInput2)
        layout.addView(keyInput3)
        layout.addView(keyInput4)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun createKeyBox(hintText: String, savedVal: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setText(savedVal)
            setSingleLine(true)
            setPadding(24, 28, 24, 28)
        }
    }
}
