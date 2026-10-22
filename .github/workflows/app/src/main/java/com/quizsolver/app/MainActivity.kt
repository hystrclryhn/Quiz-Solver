package com.quizsolver.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityLabel: TextView
    private val keyEditors = mutableListOf<EditText>()

    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("quiz_solver_prefs", MODE_PRIVATE)

        opacitySeek = findViewById(R.id.opacitySeek)
        opacityLabel = findViewById(R.id.opacityLabel)

        keyEditors.addAll(
            listOf(
                findViewById(R.id.key1),
                findViewById(R.id.key2),
                findViewById(R.id.key3),
                findViewById(R.id.key4),
                findViewById(R.id.key5),
                findViewById(R.id.key6),
                findViewById(R.id.key7),
                findViewById(R.id.key8)
            )
        )

        loadKeys()

        opacitySeek.max = 100
        opacitySeek.progress = prefs.getInt("opacity", 85)
        opacityLabel.text = "${opacitySeek.progress}%"

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityLabel.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveSettings()
            }
        })

        mediaProjectionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val data = result.data
                    if (data != null) {
                        startOverlayService(result.resultCode, data)
                        moveTaskToBack(true)
                    } else {
                        Toast.makeText(this, "Screen capture data missing", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
                }
            }

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    launchMediaProjectionPrompt()
                } else {
                    Toast.makeText(
                        this,
                        "Notification permission is required on Android 13+",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            startOverlayFlow()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Overlay service stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadKeys() {
        for (i in keyEditors.indices) {
            val saved = prefs.getString("key_${i + 1}", "") ?: ""
            keyEditors[i].setText(saved)
        }
    }

    private fun saveSettings() {
        val editor = prefs.edit()

        for (i in keyEditors.indices) {
            editor.putString("key_${i + 1}", keyEditors[i].text.toString().trim())
        }

        editor.putInt("opacity", opacitySeek.progress)
        editor.apply()
    }

    private fun startOverlayFlow() {
        saveSettings()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Enable overlay permission, then tap Start again",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        launchMediaProjectionPrompt()
    }

    private fun launchMediaProjectionPrompt() {
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java)
        intent.putExtra("result_code", resultCode)
        intent.putExtra("result_data", data)
        ContextCompat.startForegroundService(this, intent)
    }
}
