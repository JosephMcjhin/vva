package com.example.vva

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vva.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val viewModel: VoiceViewModel by viewModels()

    private data class CpuSnapshot(val idle: Long, val total: Long)
    private data class ProcessCpuSnapshot(
        val processTicks: Long,
        val elapsedMs: Long,
        val cpuCount: Int,
        val ticksPerSecond: Long
    )

    private lateinit var bind: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentConnectState = "disconnected"
    private var currentDashscopeState = "disconnected"
    private var currentDashscopeError = ""
    private var currentImuState = "waiting"
    private var currentBeepInfo = ""
    private var currentPerfInfo = "CPU -- | MEM --"
    private var imageManager: ImageManager? = null
    private var isCameraOn = false
    private var areChannelsSwapped = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            toast("需要麦克风权限")
        } else {
            bind.btnConnect.isEnabled = true
            viewModel.initialize(this, imageManager)
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            toast("需要相机权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bind.previewView.visibility = View.GONE
        bind.btnConnect.isEnabled = false
        bind.btnConnect.setOnClickListener {
            viewModel.initialize(this, imageManager)
        }
        bind.btnCamera.setOnClickListener {
            toggleCamera()
        }
        bind.btnChannelSwap.setOnClickListener {
            areChannelsSwapped = !areChannelsSwapped
            viewModel.setBeepChannelSwap(areChannelsSwapped)
            bind.btnChannelSwap.text = if (areChannelsSwapped) "声道交换" else "声道正常"
        }

        checkAndRequestPermissions()

        lifecycleScope.launch {
            viewModel.connectState.collect { state ->
                currentConnectState = state
                renderStatus()
            }
        }

        lifecycleScope.launch {
            viewModel.dashscopeState.collect { state ->
                currentDashscopeState = state
                renderStatus()
            }
        }

        lifecycleScope.launch {
            viewModel.dashscopeError.collect { error ->
                currentDashscopeError = error
                renderStatus()
            }
        }

        lifecycleScope.launch {
            viewModel.imuState.collect { state ->
                currentImuState = state
                renderStatus()
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

        lifecycleScope.launch {
            viewModel.beepInfo.collect { info ->
                currentBeepInfo = info
                renderStatus()
            }
        }

        startPerformanceMonitor()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清除标志
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scope.cancel()
        stopCameraPreview()
    }

    private fun checkAndRequestPermissions() {
        val needs = arrayOf(Manifest.permission.RECORD_AUDIO)
        val notGranted = needs.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissions.launch(notGranted.toTypedArray())
        } else {
            bind.btnConnect.isEnabled = true
            viewModel.initialize(this, imageManager)
        }
    }

    private fun toggleCamera() {
        if (isCameraOn) {
            stopCameraPreview()
            return
        }

        val permissionState =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        lifecycleScope.launch {
            try {
                val manager = imageManager ?: ImageManager(this@MainActivity, bind.previewView)
                    .also { imageManager = it }
                bind.previewView.visibility = View.VISIBLE
                manager.startCamera()
                isCameraOn = true
                bind.btnCamera.text = "相机开"
                viewModel.setImageManager(manager)
            } catch (_: Exception) {
                bind.previewView.visibility = View.GONE
                isCameraOn = false
                bind.btnCamera.text = "相机关"
                viewModel.setImageManager(null)
                toast("相机启动失败")
            }
        }
    }

    private fun stopCameraPreview() {
        imageManager?.stopCamera()
        imageManager = null
        isCameraOn = false
        if (::bind.isInitialized) {
            bind.previewView.visibility = View.GONE
            bind.btnCamera.text = "相机关"
        }
        viewModel.setImageManager(null)
    }

    private fun startPerformanceMonitor() {
        lifecycleScope.launch(Dispatchers.Default) {
            var lastCpuSnapshot = readSystemCpuSnapshot()
            var lastProcessCpuSnapshot = readProcessCpuSnapshot()
            while (isActive) {
                delay(1000)
                val nowCpuSnapshot = readSystemCpuSnapshot()
                val procStatCpuPercent =
                    if (lastCpuSnapshot != null && nowCpuSnapshot != null) {
                        calculateSystemCpuPercent(lastCpuSnapshot, nowCpuSnapshot)
                    } else {
                        null
                    }
                lastCpuSnapshot = nowCpuSnapshot

                val shouldTryFallback = procStatCpuPercent == null || procStatCpuPercent < 0.5
                val topCpuPercent = if (shouldTryFallback) readTopCpuPercent() else null

                val nowProcessCpuSnapshot = readProcessCpuSnapshot()
                val summedProcessCpuPercent =
                    if (shouldTryFallback &&
                        lastProcessCpuSnapshot != null && nowProcessCpuSnapshot != null
                    ) {
                        calculateProcessCpuPercent(lastProcessCpuSnapshot, nowProcessCpuSnapshot)
                    } else {
                        null
                    }
                lastProcessCpuSnapshot = nowProcessCpuSnapshot

                val cpuPercent =
                    listOfNotNull(procStatCpuPercent, topCpuPercent, summedProcessCpuPercent)
                        .maxOrNull()

                val memoryInfo = readSystemMemoryInfo()
                val cpuText = cpuPercent?.let {
                    String.format(Locale.US, "%.0f%%", it)
                } ?: "N/A"
                currentPerfInfo =
                    String.format(
                        Locale.US,
                        "SYS CPU %s | SYS MEM %.0f%% (%.1f/%.1fGB)",
                        cpuText,
                        memoryInfo.usedPercent,
                        memoryInfo.usedGb,
                        memoryInfo.totalGb
                    )
                withContext(Dispatchers.Main) {
                    renderStatus()
                }
            }
        }
    }

    private fun readSystemCpuSnapshot(): CpuSnapshot? {
        return try {
            val cpuLine = File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            } ?: return null
            val values = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 5) return null
            val idle = values[3] + values[4]
            CpuSnapshot(idle = idle, total = values.sum())
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateSystemCpuPercent(previous: CpuSnapshot, current: CpuSnapshot): Double? {
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L) return null
        return ((totalDelta - idleDelta).toDouble() / totalDelta.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
    }

    private fun readProcessCpuSnapshot(): ProcessCpuSnapshot? {
        return try {
            val procDir = File("/proc")
            val totalTicks = procDir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.sumOf { readProcessTicks(it) }
                ?: return null
            ProcessCpuSnapshot(
                processTicks = totalTicks,
                elapsedMs = SystemClock.elapsedRealtime(),
                cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readProcessTicks(processDir: File): Long {
        return try {
            val stat = File(processDir, "stat").readText()
            val endOfName = stat.lastIndexOf(')')
            if (endOfName < 0) return 0L
            val fields = stat.substring(endOfName + 2).trim().split(Regex("\\s+"))
            val userTicks = fields.getOrNull(11)?.toLongOrNull() ?: 0L
            val systemTicks = fields.getOrNull(12)?.toLongOrNull() ?: 0L
            userTicks + systemTicks
        } catch (_: Exception) {
            0L
        }
    }

    private fun calculateProcessCpuPercent(
        previous: ProcessCpuSnapshot,
        current: ProcessCpuSnapshot
    ): Double? {
        val tickDelta = current.processTicks - previous.processTicks
        val elapsedMs = current.elapsedMs - previous.elapsedMs
        if (tickDelta < 0L || elapsedMs <= 0L) return null
        val capacityTicks =
            elapsedMs / 1000.0 * current.ticksPerSecond.toDouble() * current.cpuCount.toDouble()
        if (capacityTicks <= 0.0) return null
        return (tickDelta.toDouble() / capacityTicks * 100.0).coerceIn(0.0, 100.0)
    }

    private fun readTopCpuPercent(): Double? {
        return try {
            val process = ProcessBuilder("top", "-b", "-n", "1")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(800, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return null
            }

            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .take(12)
                    .mapNotNull { parseTopCpuLine(it) }
                    .firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTopCpuLine(line: String): Double? {
        val lower = line.lowercase(Locale.US)
        if (!lower.contains("cpu")) return null

        val idle = Regex("""(\d+(?:\.\d+)?)%\s*(?:idle|id)\b""")
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        if (idle == null) {
            return parseTopBusyPercent(lower)
        }

        val total = Regex("""(\d+(?:\.\d+)?)%\s*cpu""")
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        return if (total != null && total > 100.0) {
            ((total - idle) / total * 100.0).coerceIn(0.0, 100.0)
        } else {
            (100.0 - idle).coerceIn(0.0, 100.0)
        }
    }

    private fun parseTopBusyPercent(line: String): Double? {
        val valueThenLabel = Regex(
            """(\d+(?:\.\d+)?)%\s*(?:user|usr|system|sys|nice|nic|iow|io|irq|sirq)\b"""
        )
        val labelThenValue = Regex(
            """(?:user|usr|system|sys|nice|nic|iow|io|irq|sirq)\s*(\d+(?:\.\d+)?)%"""
        )
        val busy = valueThenLabel.findAll(line).sumOf {
            it.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        }.takeIf { it > 0.0 }
            ?: labelThenValue.findAll(line).sumOf {
                it.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            }.takeIf { it > 0.0 }
            ?: return null
        val total = Regex("""(\d+(?:\.\d+)?)%\s*cpu""")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        return if (total != null && total > 100.0) {
            (busy / total * 100.0).coerceIn(0.0, 100.0)
        } else {
            busy.coerceIn(0.0, 100.0)
        }
    }

    private data class SystemMemoryInfo(
        val usedPercent: Double,
        val usedGb: Double,
        val totalGb: Double
    )

    private fun readSystemMemoryInfo(): SystemMemoryInfo {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val total = info.totalMem.coerceAtLeast(1L)
        val used = (total - info.availMem).coerceAtLeast(0L)
        val gb = 1024.0 * 1024.0 * 1024.0
        return SystemMemoryInfo(
            usedPercent = used.toDouble() / total.toDouble() * 100.0,
            usedGb = used / gb,
            totalGb = total / gb
        )
    }

    private fun renderStatus() {
        val soundInfo = currentBeepInfo.takeIf { it.isNotBlank() } ?: "Sound: idle"
        val dashscopeErrorInfo = currentDashscopeError.takeIf { it.isNotBlank() }
            ?.let { "\nDashScope error: $it" }
            ?: ""
        bind.statusText.text =
            "Nav: $currentConnectState | DashScope: $currentDashscopeState" +
                dashscopeErrorInfo +
                "\n" +
                "IMU: $currentImuState | $soundInfo\n$currentPerfInfo"
    }
}
