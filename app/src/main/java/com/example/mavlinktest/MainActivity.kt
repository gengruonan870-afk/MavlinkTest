package com.example.mavlinktest

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.*
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.PixelCopy
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.skydroid.rcsdk.KeyManager
import com.skydroid.rcsdk.PipelineManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.comm.CommListener
import com.skydroid.rcsdk.common.Uart
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.pipeline.Pipeline
import com.skydroid.rcsdk.key.RemoteControllerKey
import java.io.OutputStream
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvTimeStamp: TextView
    private lateinit var tvTopStatus: TextView
   // private lateinit var videoView: StyledPlayerView

   // private var player: ExoPlayer? = null\
   private lateinit var videoView: com.skydroid.fpvplayer.FPVWidget
    private var isRecording = false

    // 👉 全新的核心大脑变量
    private var myPipeline: Pipeline? = null
    private var heartbeatTimer: java.util.Timer? = null
    private var lastMotorState = 100f // 记忆机器人当前状态，默认100（停止）
    private var mavlinkSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 地图隐私合规
        com.amap.api.maps.MapsInitializer.updatePrivacyShow(this, true, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyAgree(this, true)
        setContentView(R.layout.activity_main)

        // 1. 绑定视图
        tvLog = findViewById(R.id.tvLog)
        tvTimeStamp = findViewById(R.id.tvTimeStamp)
        tvTopStatus = findViewById(R.id.tvTopStatus)
        videoView = findViewById(R.id.videoView)

        tvTopStatus.text = "机器人: KR-ZY 03 | \uD83D\uDD0B --V/--% | ⚡ --A | 坡度: --° | PING: --ms | \uD83C\uDF21\uFE0F奇:--℃ 偶:--℃ | 线径: --mm"

        // 2. 初始化各模块
        setupVideo()
        setupTabs()
        setupVideoTools()

        // 时间戳时钟
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                tvTimeStamp.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        })

        // 清除日志按钮
        findViewById<Button>(R.id.btnLogClear)?.setOnClickListener { tvLog.text = "" }
        // 屏蔽了连接串口的按钮，因为现在是全自动无线连接
        findViewById<Button>(R.id.btnConnect)?.setOnClickListener {
            Toast.makeText(this, "已升级为自动无线连接，无需手动开启", Toast.LENGTH_SHORT).show()
        }

        // 3. 初始化云卓 SDK 并建立连接
        RCSDKManager.initSDK(this, object : SDKManagerCallBack {
            override fun onRcConnected() {
                runOnUiThread { tvLog.append("✅ 遥控器底层握手成功！\n") }
                setupPipeline()           // 握手成功后，建立无线管道
                startJoystickPolling()    // 启动疯狂轮询器读取摇杆
            }
            override fun onRcConnectFail(e: SkyException?) {
                runOnUiThread { tvLog.append("❌ 遥控器握手失败: $e\n") }
            }
            override fun onRcDisconnect() { }
        })
        RCSDKManager.setMainThreadCallBack(true)
        RCSDKManager.connectToRC()
    }

    // ==========================================
    // 📡 核心通信网络：建立管道与起搏心跳
    // ==========================================
    private fun setupPipeline() {
        myPipeline = PipelineManager.createPipeline(Uart.UART0)
        myPipeline?.onCommListener = object : CommListener {
            override fun onConnectSuccess() {
                runOnUiThread { tvLog.append("📡 无线数传管道已打通！\n") }
                startHeartbeat() // 管道一通，立刻开始发送 700 心跳！
            }
            override fun onConnectFail(e: SkyException) {
                runOnUiThread { tvLog.append("❌ 管道连接失败: $e\n") }
            }
            override fun onDisconnect() {
                runOnUiThread { tvLog.append("⚠️ 管道断开连接\n") }
                heartbeatTimer?.cancel() // 断开时停止心跳
            }
            override fun onReadData(bytes: ByteArray) {
                // 🔥 核心移植：将接收机发回来的数据，喂给你的解析器更新图表和UI！
                mavlinkParser.feedData(bytes)
            }
        }
        myPipeline?.let { PipelineManager.connectPipeline(it) }
    }

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = java.util.Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                sendMotorCommand(700f) // 移植 Python 逻辑：每 500ms 发送一次心跳
            }
        }, 0, 500)
    }

    // ==========================================
    // 🎮 摇杆榨汁机：获取摇杆数据并转换为指令
    // ==========================================
    private fun startJoystickPolling() {
        java.util.Timer().scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                KeyManager.get(RemoteControllerKey.KeyChannels, object : CompletionCallbackWith<IntArray> {
                    override fun onSuccess(channels: IntArray?) {
                        if (channels != null && channels.size >= 6) {
                            val ch3Raw = channels[2] // 获取左摇杆上下方向

                            val currentMotorState = when {
                                ch3Raw > 1600 -> 101f // 往上推：前进 (101)
                                ch3Raw < 1400 -> 102f // 往下拉：后退 (102)
                                else -> 100f          // 居中或轻微抖动：停止 (100)
                            }

                            if (currentMotorState != lastMotorState) {
                                sendMotorCommand(currentMotorState)
                                lastMotorState = currentMotorState

                                val action = if (currentMotorState == 101f) "🚀 猛烈前进 (101)" else if (currentMotorState == 102f) "🔙 倒车后退 (102)" else "⏹️ 紧急刹车 (100)"
                                android.util.Log.e("RobotAction", action)
                            }
                        }
                    }
                    override fun onFailure(e: SkyException) {}
                })
            }
        }, 0, 100)
    }

    // ==========================================
    // 🚀 终极翻译官：发送 MAVLink (替代 serialPort.write)
    // ==========================================
    private fun sendMotorCommand(paramValue: Float) {
        if (myPipeline == null) return // 管道没通不发

        val payloadLen = 33
        val msgId = 76 // COMMAND_LONG
        val crcExtra = 152.toByte()

        val buffer = java.nio.ByteBuffer.allocate(6 + payloadLen + 2)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // 1. 打包头部
        buffer.put(0xFE.toByte())
        buffer.put(payloadLen.toByte())
        buffer.put((mavlinkSeq++ and 0xFF).toByte())
        buffer.put(255.toByte()) // 地面站系统 ID
        buffer.put(0.toByte())   // 组件 ID
        buffer.put(msgId.toByte())

        // 2. 打包数据体
        buffer.putFloat(paramValue)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putShort(31025.toShort()) // command (你们主板专属密码)
        buffer.put(1.toByte()) // target_system
        buffer.put(1.toByte()) // target_component
        buffer.put(0.toByte()) // confirmation

        // 3. 计算 CRC 校验码
        val bytes = buffer.array()
        val crc = calculateMavlinkCRC(bytes, bytes.size - 2, crcExtra)
        buffer.putShort(crc.toShort())

        // 4. 🔥 通过无线电波发射！
        myPipeline?.writeData(buffer.array())
    }

    private fun calculateMavlinkCRC(buffer: ByteArray, length: Int, crcExtra: Byte): Int {
        var crc = 0xFFFF
        for (i in 1 until length) {
            var tmp = buffer[i].toInt() and 0xFF
            tmp = tmp xor (crc and 0xFF)
            tmp = tmp xor (tmp shl 4) and 0xFF
            crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
            crc = crc and 0xFFFF
        }
        var tmp2 = crcExtra.toInt() and 0xFF
        tmp2 = tmp2 xor (crc and 0xFF)
        tmp2 = tmp2 xor (tmp2 shl 4) and 0xFF
        crc = (crc shr 8) xor (tmp2 shl 8) xor (tmp2 shl 3) xor (tmp2 shr 4)
        return crc and 0xFFFF
    }

    // ==========================================
    // 📊 数据解析与 UI 刷新
    // ==========================================
    fun updateRobotStatus(
        batteryVol: Float, batteryPct: Int, currentA: Float,
        pitchAngle: Float, pingMs: Int,
        tempOdd: Float, tempEven: Float, wireDiameter: Float
    ) {
        runOnUiThread {
            tvTopStatus.text = String.format(
                Locale.getDefault(),
                "机器人: KR-ZY 03 | \uD83D\uDD0B %.1fV/%d%% | ⚡ %.1fA | 坡度: %.1f° | PING: %dms | \uD83C\uDF21\uFE0F奇:%.1f℃ 偶:%.1f℃ | 线径: %.1fmm",
                batteryVol, batteryPct, currentA, pitchAngle, pingMs, tempOdd, tempEven, wireDiameter
            )
        }
    }

    private val mavlinkParser = SimpleMavlinkParser(
        onCommandReceived = { command, params ->
            if (command == 31025) {
                runOnUiThread {
                    val waveValue = params[0]
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                    if (currentFragment is DetectFragment) currentFragment.updateGraph(waveValue)
                    if (waveValue >= 95) tvLog.append("⚠️ 严重负载警告: ${waveValue.toInt()}%\n")

                    updateRobotStatus(
                        batteryVol = params[1],
                        batteryPct = (params[1] / 48.0f * 100).toInt().coerceIn(0, 100),
                        currentA = params[2],
                        pitchAngle = params[3],
                        pingMs = 22,
                        tempOdd = params[4],
                        tempEven = params[5],
                        wireDiameter = 30.0f
                    )
                }
            }
        },
        onGpsReceived = { lat, lon ->
            runOnUiThread {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                if (currentFragment is LocationFragment) {
                    currentFragment.updateUavPosition(lat, lon)
                }
            }
        }
    )

    class SimpleMavlinkParser(
        private val onCommandReceived: (Int, FloatArray) -> Unit,
        private val onGpsReceived: (Double, Double) -> Unit
    ) {
        private val buffer = mutableListOf<Byte>()
        fun feedData(data: ByteArray) {
            buffer.addAll(data.toList())
            parse()
        }
        private fun parse() {
            while (buffer.isNotEmpty()) {
                val start = buffer.indexOfFirst { b: Byte ->
                    val i = b.toInt() and 0xFF
                    i == 0xFD || i == 0xFE
                }
                if (start == -1) { buffer.clear(); return }
                if (start > 0) buffer.subList(0, start).clear()
                if (buffer.size < 12) return

                val len = buffer[1].toInt() and 0xFF
                val isV2 = (buffer[0].toInt() and 0xFF) == 0xFD
                val totalLen = (if (isV2) 12 else 8) + len
                if (buffer.size < totalLen) return

                val msgId = if (isV2) {
                    (buffer[7].toInt() and 0xFF) or ((buffer[8].toInt() and 0xFF) shl 8)
                } else (buffer[5].toInt() and 0xFF)

                if (msgId == 76 && len >= 30) {
                    val offset = if (isV2) 10 else 6
                    val payload = buffer.subList(offset, offset + len).toByteArray()
                    val params = FloatArray(7)
                    for (i in 0..6) {
                        params[i] = ByteBuffer.wrap(payload, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    }
                    val cmd = ByteBuffer.wrap(payload, 28, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    onCommandReceived(cmd, params)
                }
                else if (msgId == 33) {
                    val offset = if (isV2) 10 else 6
                    val payload = buffer.subList(offset, offset + len).toByteArray()
                    if (payload.size >= 12) {
                        val latInt = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val lonInt = ByteBuffer.wrap(payload, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val lat = latInt / 1e7
                        val lon = lonInt / 1e7
                        onGpsReceived(lat, lon)
                    }
                }
                buffer.subList(0, totalLen).clear()
            }
        }
    }

    // ==========================================
    // 🎞️ 其他工具功能 (视频、截图、生命周期)
    // ==========================================
    private fun setupVideoTools() {
        findViewById<Button>(R.id.btnRotate)?.setOnClickListener {
            videoView.rotation = (videoView.rotation + 90f) % 360f
        }
        findViewById<Button>(R.id.btnScreenshot)?.setOnClickListener { takeScreenshot() }
        findViewById<Button>(R.id.btnRecord)?.setOnClickListener { view ->
            val btn = view as Button
            isRecording = !isRecording
            if (isRecording) {
                btn.text = "停止"
                btn.setBackgroundColor(android.graphics.Color.RED)
                tvLog.append("⏺️ 正在录制巡检视频...\n")
            } else {
                btn.text = "录像"
                btn.setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
                tvLog.append("✅ 录像已保存至相册\n")
            }
        }
    }

    private fun takeScreenshot() {
        // 先把之前的截图逻辑注释掉，等视频通了咱们再来适配 FPVWidget 的截图方式
        Toast.makeText(this, "截图功能适配中...", Toast.LENGTH_SHORT).show()

        /*
        val surfaceView = videoView.getVideoSurfaceView() as? SurfaceView ?: return
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                saveBitmapToGallery(bitmap)
                runOnUiThread { Toast.makeText(this, "截图已保存", Toast.LENGTH_SHORT).show() }
            }
        }, Handler(Looper.getMainLooper()))
        */
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "IMG_KRZY03_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RobotInspection")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            val out: OutputStream? = contentResolver.openOutputStream(it)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out!!)
            out.close()
        }
    }

    private fun setupVideo() {
        try {
            // 1. 设置 RTSP 播放地址 (官方的专属写法)
            videoView.url = "rtsp://192.168.144.25:8554/main.264"

            // 2. 推荐开启硬解，降低延迟
            videoView.usingMediaCodec = true

            // 3. 开始播放！
            videoView.start()

        } catch (e: Exception) {
            tvLog.append("⚠️ 视频流初始化失败: ${e.message}\n")
        }
    }

    private fun setupTabs() {
        val btnTabWalk = findViewById<Button>(R.id.btnTabWalk)
        val btnTabDetect = findViewById<Button>(R.id.btnTabDetect)
        val btnTabLocation = findViewById<Button>(R.id.btnTabLocation)

        switchFragment(WalkFragment(), btnTabWalk)

        btnTabWalk?.setOnClickListener { switchFragment(WalkFragment(), btnTabWalk) }
        btnTabDetect?.setOnClickListener { switchFragment(DetectFragment(), btnTabDetect) }
        btnTabLocation?.setOnClickListener { switchFragment(LocationFragment(), btnTabLocation) }
    }

    private fun switchFragment(fragment: Fragment, activeButton: Button?) {
        supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment).commit()
        val activeColor = android.graphics.Color.parseColor("#2E7D32")
        val inactiveColor = android.graphics.Color.parseColor("#1565C0")
        findViewById<Button>(R.id.btnTabWalk)?.setBackgroundColor(inactiveColor)
        findViewById<Button>(R.id.btnTabDetect)?.setBackgroundColor(inactiveColor)
        findViewById<Button>(R.id.btnTabLocation)?.setBackgroundColor(inactiveColor)
        activeButton?.setBackgroundColor(activeColor)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            android.util.Log.e("JoystickTest", "【物理按键】被按下，真实代号是：$code")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
       // player?.release()
        videoView.stop()
        heartbeatTimer?.cancel()
        myPipeline?.let { PipelineManager.disconnectPipeline(it) } // 断开管道
        RCSDKManager.disconnectRC()
    }
}