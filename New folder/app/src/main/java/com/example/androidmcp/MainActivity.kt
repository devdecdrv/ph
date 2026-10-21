package com.example.androidmcp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Android MCP"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 24) }
        info = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(0, 0, 0, 24) }
        col.addView(title)
        col.addView(status)
        col.addView(info)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            })
        }

        button("1. Accessibility settings (enable Android MCP Control)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("3. Grant permissions (calls, SMS, location, notifications)") {
            val perms = mutableListOf(
                Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        }
        button("4. All files access (optional, for shared storage)") {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        button("5. Start server") {
            startForegroundService(Intent(this, McpForegroundService::class.java))
            status.postDelayed({ refresh() }, 500)
        }
        button("Stop server") {
            stopService(Intent(this, McpForegroundService::class.java))
            status.postDelayed({ refresh() }, 500)
        }
        button("Copy token") {
            copy("token", App.token())
        }
        button("Copy MCP URL") {
            copy("url", "http://${ip()}:${App.PORT}/mcp/${App.token()}")
        }
        button("Generate new token") {
            App.regenerateToken()
            stopService(Intent(this, McpForegroundService::class.java))
            Toast.makeText(this, "New token created. Start the server again.", Toast.LENGTH_LONG).show()
            status.postDelayed({ refresh() }, 500)
        }

        val micResult = TextView(this).apply { textSize = 13f; setPadding(0, 24, 0, 24) }
        button("Call test: can this phone hear a call? (tap during a call)") { runMicTest(micResult) }
        col.addView(micResult)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    /**
     * During a phone call, records 5 seconds from each microphone source and reports whether
     * real sound arrived or only silence (Android silences the mic for apps during calls on many phones).
     */
    private fun runMicTest(out: TextView) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            out.text = "Allow the microphone permission, then tap the test again."
            return
        }
        out.text = "Testing for about 15 seconds. Keep the call on speaker and have the other person talk."
        Thread {
            val sources = listOf(
                "MIC" to MediaRecorder.AudioSource.MIC,
                "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
                "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            val am = getSystemService(AudioManager::class.java)
            val sb = StringBuilder()
            sb.append("Audio mode: ${am.mode} (2 or 3 means a call is active, 0 means no call)\n")
            sb.append("Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
            for ((name, src) in sources) {
                sb.append(name).append(": ").append(recordPeak(src)).append('\n')
            }
            sb.append("\nIf every source says SILENT while the other person was talking, this phone blocks call audio from apps.")
            runOnUiThread { out.text = sb.toString() }
        }.start()
    }

    private fun recordPeak(source: Int): String {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "not supported"
        val buf = ShortArray(maxOf(minBuf, rate) / 2)
        val rec = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf.size * 2)
        } catch (t: Throwable) {
            return "could not open (${t.message})"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "could not open"
        }
        var peak = 0
        var total = 0L
        var samples = 0L
        try {
            rec.startRecording()
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (k in 0 until n) {
                    val v = kotlin.math.abs(buf[k].toInt())
                    if (v > peak) peak = v
                    total += v
                }
                samples += n
            }
        } catch (t: Throwable) {
            return "error (${t.message})"
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
        val avg = if (samples > 0) total / samples else 0
        return if (peak < 5) "SILENT (peak $peak)" else "HEARD SOUND (peak $peak, average $avg)"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val a11y = AgentAccessibilityService.instance != null
        val notif = AgentNotificationListener.instance != null
        status.text = buildString {
            append("Server: ").append(if (McpForegroundService.running) "running" else "stopped").append('\n')
            append("Accessibility: ").append(if (a11y) "enabled" else "NOT enabled").append('\n')
            append("Notification access: ").append(if (notif) "enabled" else "NOT enabled")
        }
        info.text = "Local URL: http://${ip()}:${App.PORT}/mcp/${App.token()}\n\n" +
            "For Claude, run a tunnel and use: https://<your-tunnel-host>/mcp/${App.token()}\n\n" +
            "Token: ${App.token()}"
    }

    private fun ip(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "<phone-ip>"
        } catch (t: Throwable) {
            "<phone-ip>"
        }
    }
}
