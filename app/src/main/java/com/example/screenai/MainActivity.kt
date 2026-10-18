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

    private lateinit var apiKeyInput: EditText
    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("API_KEY", apiKeyInput.text.toString().trim())
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

        // Safe padding for Android 16 edge-to-edge screens
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 260, 60, 60)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "ScreenAI Setup"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 40)
        }

        apiKeyInput = EditText(this).apply {
            hint = "Paste Gemini API Key here"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setText(prefs.getString("API_KEY", ""))
        }

        val startBtn = Button(this).apply {
            text = "Start AI Overlay"
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(context, "Enter your Gemini API Key first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("API_KEY", key).apply()

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
        layout.addView(apiKeyInput)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }
}
