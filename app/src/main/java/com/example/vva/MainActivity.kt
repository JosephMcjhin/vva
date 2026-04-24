package com.example.vva

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vva.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private val viewModel: VoiceViewModel by viewModels()

    private lateinit var bind: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var imageManager: ImageManager? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            toast("需要相机 + 麦克风权限")
        } else {
            scope.launch { startCamera() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bind.btnConnect.setOnClickListener { viewModel.initialize(this, imageManager!!) }

        checkAndRequestPermissions()

        lifecycleScope.launch {
            viewModel.connectState.collect { state ->
                bind.statusText.text = "Status: $state"
            }
        }

        lifecycleScope.launch {
            viewModel.userText.collect { text ->
                if (text.isNotBlank()) {
                    bind.userText.text = text
                    bind.userText.visibility = android.view.View.VISIBLE
                } else {
                    bind.userText.visibility = android.view.View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.aiText.collect { text ->
                if (text.isNotBlank()) {
                    bind.aiText.text = text
                    bind.aiText.visibility = android.view.View.VISIBLE
                } else {
                    bind.aiText.visibility = android.view.View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清除标志
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private fun checkAndRequestPermissions() {
        val needs = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val notGranted = needs.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissions.launch(notGranted.toTypedArray())
        } else {
            scope.launch {
                startCamera()
            }
        }
    }

    private suspend fun startCamera() {
        if (imageManager == null) {
            imageManager = ImageManager(this, bind.previewView)
        }
        imageManager?.startCamera()
    }
}
