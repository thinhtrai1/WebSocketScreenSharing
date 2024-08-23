package com.app.screensharing

import android.Manifest.permission.POST_NOTIFICATIONS
import android.R.attr
import android.R.attr.label
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged


class MainActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            ScreenCaptureService.startService(this, RESULT_OK, it.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#33000000")),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#33000000")),
        )
        setContentView(R.layout.activity_main)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<TextView>(R.id.tvIpAddress).apply {
            text = HttpServer.getLocalIpAddress()?.plus(":${HttpServer.PORT}")
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnStart).setOnClickListener {
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
        findViewById<View>(R.id.btnStop).setOnClickListener {
            ScreenCaptureService.stopService(this)
        }
        findViewById<Switch>(R.id.swPin).apply {
            isChecked = HttpServer.Settings.enablePin
            setOnCheckedChangeListener { _, isChecked ->
                HttpServer.Settings.enablePin = isChecked
            }
        }
        findViewById<SeekBar>(R.id.sbQuality).apply {
            progress = HttpServer.Settings.imageQuality
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    HttpServer.Settings.imageQuality = progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                }
            })
        }
        findViewById<EditText>(R.id.edtPin).doAfterTextChanged {
            HttpServer.Settings.pin = it.toString()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(POST_NOTIFICATIONS), 0)
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenCaptureService.stopService(this)
    }
}