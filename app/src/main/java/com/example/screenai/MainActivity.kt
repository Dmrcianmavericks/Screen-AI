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

    private val keyInputs = mutableListOf<EditText>()
    private lateinit var opacitySeekBar: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val combinedKeys = keyInputs
                .map { it.text.toString().trim() }
                .filter { it.length > 12 }
                .joinToString(",")

            val chosenOpacity = opacitySeekBar.progress

            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("API_KEY", combinedKeys)
                putExtra("OPACITY_PERCENT", chosenOpacity)
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
            setPadding(60, 140, 60, 80)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "ScreenAI (Ultra-Fast 8-Key Engine)"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        }

        val subtitle = TextView(this).apply {
            text = "Paste 2 to 8 Gemini keys below. Uses 0-thinking instant mode for ~1.0s responses."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        }

        layout.addView(title)
        layout.addView(subtitle)

        val savedOpacity = prefs.getInt("OVERLAY_OPACITY", 75)

        opacityLabel = TextView(this).apply {
            text = "Stealth Overlay Opacity: $savedOpacity%"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, 10, 0, 8)
        }

        opacitySeekBar = SeekBar(this).apply {
            max = 100
            progress = savedOpacity
            setPadding(10, 10, 10, 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    opacityLabel.text = "Stealth Overlay Opacity: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        layout.addView(opacityLabel)
        layout.addView(opacitySeekBar)

        keyInputs.clear()
        for (i in 1..8) {
            val savedKey = prefs.getString("SAFE_KEY_$i", "") ?: ""
            val box = createKeyBox("Key $i (AQ. / AIza)", savedKey)
            keyInputs.add(box)
            layout.addView(box)
        }

        val startBtn = Button(this).apply {
            text = "Start AI Overlay"
            setOnClickListener {
                val activeKeys = keyInputs.map { it.text.toString().trim() }.filter { it.length > 12 }
                if (activeKeys.isEmpty()) {
                    Toast.makeText(context, "Paste at least 1 API key!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val editor = prefs.edit()
                for (i in 1..8) {
                    editor.putString("SAFE_KEY_$i", keyInputs[i - 1].text.toString().trim())
                }
                editor.putInt("OVERLAY_OPACITY", opacitySeekBar.progress)
                editor.apply()

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
                for (box in keyInputs) box.setText("")
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
            setPadding(24, 22, 24, 22)
        }
    }
}
