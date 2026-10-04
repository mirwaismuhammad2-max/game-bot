package com.mu.gamebot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
            toast("Screen capture started")
        } else {
            toast("Screen capture not allowed")
        }
        ui.postDelayed({ refresh() }, 600)
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun bot() = GameBotAccessibilityService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { action(); ui.postDelayed({ refresh() }, 300) }
            })
        }

        button("1. Enable Accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Start screen capture") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
        button("Start auto-close ads") {
            val b = bot()
            if (b == null) toast("Do step 1 first") else b.startAdWatcher()
        }
        button("Stop auto-close ads") { bot()?.stopAdWatcher() }
        button("Test tap at screen centre (in 3 s)") {
            val b = bot()
            if (b == null) toast("Do step 1 first") else {
                val m = resources.displayMetrics
                toast("Tapping in 3 seconds…")
                ui.postDelayed({ b.tap(m.widthPixels / 2f, m.heightPixels / 2f) }, 3000)
            }
        }
        button("Stop screen capture") { ScreenCaptureService.stop(this) }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        fun mark(on: Boolean) = if (on) "✅ ON" else "❌ OFF"
        status.text = buildString {
            appendLine("Accessibility service: ${mark(GameBotAccessibilityService.isConnected)}")
            appendLine("Screen capture: ${mark(ScreenCaptureService.isCapturing)}")
            append("Auto-close ads: ${mark(bot()?.isAdWatcherOn == true)}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
