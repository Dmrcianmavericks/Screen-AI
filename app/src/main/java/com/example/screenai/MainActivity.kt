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

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val combinedKeys = listOf(
                keyInput1.text.toString().trim(),
                keyInput2.text.toString().trim(),
                keyInput3.text.toString().trim(),
                keyInput4.text.toString().trim()
            ).filter { it.length > 12 }.joinToString(",")

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
            setPadding(60, 200, 60, 80)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "ScreenAI (Gemini + Groq Engine)"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 14)
        }

        val subtitle = TextView(this).apply {
            text = "Supports Gemini (AQ. / AIza) AND Groq (gsk_) keys in any box! Mix or match up to 4 keys. Never paste keys on GitHub."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 28)
        }

        keyInput1 = createKeyBox("Key 1 (AQ. / AIza / gsk_)", prefs.getString("SAFE_KEY_1", "") ?: "")
        keyInput2 = createKeyBox("Key 2 (AQ. / AIza / gsk_)", prefs.getString("SAFE_KEY_2", "") ?: "")
        keyInput3 = createKeyBox("Key 3 (AQ. / AIza / gsk_)", prefs.getString("SAFE_KEY_3", "") ?: "")
        keyInput4 = createKeyBox("Key 4 (AQ. / AIza / gsk_)", prefs.getString("SAFE_KEY_4", "") ?: "")

        val startBtn = Button(this).apply {
            text = "Start AI Overlay"
            setOnClickListener {
                val k1 = keyInput1.text.toString().trim()
                val k2 = keyInput2.text.toString().trim()
                val k3 = keyInput3.text.toString().trim()
                val k4 = keyInput4.text.toString().trim()

                if (k1.isEmpty() && k2.isEmpty() && k3.isEmpty() && k4.isEmpty()) {
                    Toast.makeText(context, "Paste at least 1 API key!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString("SAFE_KEY_1", k1)
                    .putString("SAFE_KEY_2", k2)
                    .putString("SAFE_KEY_3", k3)
                    .putString("SAFE_KEY_4", k4)
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

        val clearBtn = Button(this).apply {
            text = "Clear All Keys"
            setOnClickListener {
                keyInput1.setText("")
                keyInput2.setText("")
                keyInput3.setText("")
                keyInput4.setText("")
                prefs.edit().clear().apply()
                Toast.makeText(context, "All keys cleared", Toast.LENGTH_SHORT).show()
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
        layout.addView(clearBtn)
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
