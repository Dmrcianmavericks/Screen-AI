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
            text = "ScreenAI (4-Key Turbo Setup)"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "Paste 1 to 4 Gemini API keys from different Gmail accounts below. The app automatically rotates them so you never hit a 429 limit."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 30)
        }

        keyInput1 = createKeyBox("Gemini API Key 1 (Required)", prefs.getString("KEY_1", "") ?: "")
        keyInput2 = createKeyBox("Gemini API Key 2 (Optional - Gmail 2)", prefs.getString("KEY_2", "") ?: "")
        keyInput3 = createKeyBox("Gemini API Key 3 (Optional - Gmail 3)", prefs.getString("KEY_3", "") ?: "")
        keyInput4 = createKeyBox("Gemini API Key 4 (Optional - Gmail 4)", prefs.getString("KEY_4", "") ?: "")

        // Migrate old single key if present
        if (keyInput1.text.isEmpty()) {
            val oldKey = prefs.getString("API_KEY", "") ?: ""
            if (oldKey.isNotEmpty()) keyInput1.setText(oldKey)
        }

        val startBtn = Button(this).apply {
            text = "Start AI Overlay"
            setOnClickListener {
                val k1 = keyInput1.text.toString().trim()
                val k2 = keyInput2.text.toString().trim()
                val k3 = keyInput3.text.toString().trim()
                val k4 = keyInput4.text.toString().trim()

                if (k1.isEmpty() && k2.isEmpty() && k3.isEmpty() && k4.isEmpty()) {
                    Toast.makeText(context, "Paste at least 1 Gemini API Key!", Toast.LENGTH_SHORT).show()
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
