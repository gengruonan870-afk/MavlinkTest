package com.example.mavlinktest

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CMD_MOTOR_CONTROL = 31025
        private const val CMD_STEPPER_CONTROL = 201
        private const val CMD_STEPPER_HOME = 202
        private const val CMD_VOLTAGE_WARNING = 31033
        private const val CMD_MOTOR_TELEMETRY = 31034
        private const val HEARTBEAT_PARAM = 700f
        private const val HEARTBEAT_ACK = 704f

        private const val MAVLINK_COMMAND_LONG_MSG_ID = 76
        private const val MAVLINK_COMMAND_LONG_PAYLOAD_LEN = 33
        private const val MAVLINK_COMMAND_LONG_CRC_EXTRA = 152.toByte()

        private const val MOTOR_STOP = 100
        private const val MOTOR_FORWARD = 101
        private const val MOTOR_BACKWARD = 102
        private const val MOTOR_MAX_RPM = 550f

        private const val LIFT_MIN_POSITION_MM = 0f
        private const val LIFT_MAX_POSITION_MM = 155f
        private const val LIFT_MAX_RPM = 850f
        private const val LIFT_MAX_STEP_MM_PER_TICK = 1.5f

        private const val RC_LOW = 1050
        private const val RC_CENTER = 1500
        private const val RC_HIGH = 1950
        private const val RC_DEAD_LOW = 1400
        private const val RC_DEAD_HIGH = 1600

        private const val HEARTBEAT_TIMEOUT_MS = 2500L
        private const val MOTOR_TELEMETRY_TIMEOUT_MS = 3000L

        private const val DRIVER_TEMP_WARN_C = 85f
        private const val DRIVER_TEMP_DANGER_C = 95f
        private const val MOTOR_TEMP_WARN_C = 65f
        private const val MOTOR_TEMP_DANGER_C = 75f
    }

    private lateinit var tvLog: TextView
    private lateinit var tvTimeStamp: TextView
    private lateinit var tvTopStatus: TextView
    private lateinit var tvMotorVoltage: TextView
    private lateinit var tvDriverTemp1: TextView
    private lateinit var tvMotorTemp1: TextView
    private lateinit var tvDriverTemp2: TextView
    private lateinit var tvMotorTemp2: TextView
    private lateinit var videoView: com.skydroid.fpvplayer.FPVWidget

    private var myPipeline: Pipeline? = null
    private var heartbeatTimer: Timer? = null
    private var joystickTimer: Timer? = null
    private var mavlinkSeq = 0
    private var isRecording = false

    private var motorCommand = MOTOR_STOP
    private var motorSpeedRpm = 0f
    private var liftMotorId = 0
    private var liftTargetPositionMm = 0f
    private var liftSpeedRpm = 0f
    private var lastSentLiftPositionMm = Float.NaN

    private var batteryVol = 0f
    private var batteryPct = 0
    private var currentA = 0f
    private var pitchAngle = 0f
    private var pingMs = 0
    private var tempOdd = 0f
    private var tempEven = 0f
    private var wireDiameter = 0f

    private var lastHeartbeatAckTime = 0L
    private var heartbeatTimeoutLogged = false

    private var motorTelemetryVoltage = Float.NaN
    private var driverTemp1 = Float.NaN
    private var motorTemp1 = Float.NaN
    private var driverTemp2 = Float.NaN
    private var motorTemp2 = Float.NaN
    private var voltageWarningLevel = 0
    private var lastMotorTelemetryTime = 0L
    private var lastVoltageWarningTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.amap.api.maps.MapsInitializer.updatePrivacyShow(this, true, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyAgree(this, true)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        tvTimeStamp = findViewById(R.id.tvTimeStamp)
        tvTopStatus = findViewById(R.id.tvTopStatus)
        tvMotorVoltage = findViewById(R.id.tvMotorVoltage)
        tvDriverTemp1 = findViewById(R.id.tvDriverTemp1)
        tvMotorTemp1 = findViewById(R.id.tvMotorTemp1)
        tvDriverTemp2 = findViewById(R.id.tvDriverTemp2)
        tvMotorTemp2 = findViewById(R.id.tvMotorTemp2)
        videoView = findViewById(R.id.videoView)
        refreshTopStatus()
        refreshMotorTelemetryStatus()

        setupVideo()
        setupTabs()
        setupVideoTools()
        startClock()

        findViewById<Button>(R.id.btnLogClear)?.setOnClickListener { tvLog.text = "" }
        findViewById<Button>(R.id.btnConnect)?.setOnClickListener {
            Toast.makeText(this, "Automatic wireless link is used.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSensorTest)?.setOnClickListener {
            startActivity(Intent(this, SensorDataTestActivity::class.java))
        }

        RCSDKManager.initSDK(this, object : SDKManagerCallBack {
            override fun onRcConnected() {
                runOnUiThread { tvLog.append("RC connected.\n") }
                setupPipeline()
                startJoystickPolling()
            }

            override fun onRcConnectFail(e: SkyException?) {
                runOnUiThread { tvLog.append("RC connect failed: $e\n") }
            }

            override fun onRcDisconnect() {
                runOnUiThread { tvLog.append("RC disconnected.\n") }
            }
        })
        RCSDKManager.setMainThreadCallBack(true)
        RCSDKManager.connectToRC()
    }

    private fun startClock() {
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                tvTimeStamp.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupPipeline() {
        myPipeline = PipelineManager.createPipeline(Uart.UART0)
        myPipeline?.onCommListener = object : CommListener {
            override fun onConnectSuccess() {
                runOnUiThread { tvLog.append("Wireless pipeline connected.\n") }
                startHeartbeat()
            }

            override fun onConnectFail(e: SkyException) {
                runOnUiThread { tvLog.append("Pipeline connect failed: $e\n") }
            }

            override fun onDisconnect() {
                runOnUiThread {
                    tvLog.append("Pipeline disconnected.\n")
                    refreshTopStatus()
                }
                heartbeatTimer?.cancel()
            }

            override fun onReadData(bytes: ByteArray) {
                mavlinkParser.feedData(bytes)
            }
        }
        myPipeline?.let { PipelineManager.connectPipeline(it) }
    }

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendHeartbeat()
                checkHeartbeatTimeout()
                checkMotorTelemetryTimeout()
            }
        }, 0, 500)
    }

    private fun sendHeartbeat() {
        sendCommandLong(
            command = CMD_MOTOR_CONTROL,
            param1 = HEARTBEAT_PARAM
        )
    }

    private fun checkHeartbeatTimeout() {
        val hasAck = lastHeartbeatAckTime > 0L
        val timedOut = hasAck && System.currentTimeMillis() - lastHeartbeatAckTime > HEARTBEAT_TIMEOUT_MS
        if (timedOut && !heartbeatTimeoutLogged) {
            heartbeatTimeoutLogged = true
            runOnUiThread {
                tvLog.append("Heartbeat timeout: board did not ack.\n")
                refreshTopStatus()
            }
        } else if (!timedOut) {
            runOnUiThread { refreshTopStatus() }
        }
    }

    private fun checkMotorTelemetryTimeout() {
        if (lastMotorTelemetryTime == 0L) return
        if (System.currentTimeMillis() - lastMotorTelemetryTime > MOTOR_TELEMETRY_TIMEOUT_MS) {
            runOnUiThread { refreshMotorTelemetryStatus() }
        }
    }

    private fun startJoystickPolling() {
        joystickTimer?.cancel()
        joystickTimer = Timer()
        joystickTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                KeyManager.get(RemoteControllerKey.KeyChannels, object : CompletionCallbackWith<IntArray> {
                    override fun onSuccess(channels: IntArray?) {
                        if (channels == null) return

                        // Measured RC channels:
                        // CH3 left stick vertical: 1050 bottom, 1500 center, 1950 top.
                        // CH2 right stick vertical: 1050 top, 1500 center, 1950 bottom.
                        // CH12 right dial: 1050 top, 1950 clockwise bottom.
                        if (channels.size > 2) {
                            handleDriveChannel(channels[2])
                        }
                        if (channels.size > 1) {
                            val ch12Raw = channels.getOrNull(11)
                            handleLiftChannels(channels[1], ch12Raw)
                        }
                        runOnUiThread { refreshTopStatus() }
                    }

                    override fun onFailure(e: SkyException) {}
                })
            }
        }, 0, 100)
    }

    private fun handleDriveChannel(ch3Raw: Int) {
        val nextCommand: Int
        val nextSpeed: Float
        when {
            ch3Raw > RC_DEAD_HIGH -> {
                nextCommand = MOTOR_FORWARD
                nextSpeed = mapChannelToRange(ch3Raw, RC_DEAD_HIGH, RC_HIGH, 0f, MOTOR_MAX_RPM)
            }
            ch3Raw < RC_DEAD_LOW -> {
                nextCommand = MOTOR_BACKWARD
                nextSpeed = mapChannelToRange(ch3Raw, RC_DEAD_LOW, RC_LOW, 0f, MOTOR_MAX_RPM)
            }
            else -> {
                // 回中时保持之前的方向，只把速度设为 0，避免发送 100 急停伤电机。
                nextCommand = if (motorCommand == MOTOR_FORWARD || motorCommand == MOTOR_BACKWARD) {
                    motorCommand
                } else {
                    MOTOR_STOP
                }
                nextSpeed = 0f
            }
        }

        motorCommand = nextCommand
        motorSpeedRpm = nextSpeed
        sendMotorCommand(nextCommand, nextSpeed)
    }

    private fun handleLiftChannels(ch2Raw: Int, ch12Raw: Int?) {
        if (ch12Raw != null) {
            liftSpeedRpm = mapChannelToRange(ch12Raw, RC_LOW, RC_HIGH, 0f, LIFT_MAX_RPM)
        }

        val delta = when {
            ch2Raw < RC_DEAD_LOW -> mapChannelToRange(ch2Raw, RC_DEAD_LOW, RC_LOW, 0f, LIFT_MAX_STEP_MM_PER_TICK)
            ch2Raw > RC_DEAD_HIGH -> -mapChannelToRange(ch2Raw, RC_DEAD_HIGH, RC_HIGH, 0f, LIFT_MAX_STEP_MM_PER_TICK)
            else -> 0f
        }

        if (delta == 0f) return

        liftTargetPositionMm = (liftTargetPositionMm + delta).coerceIn(LIFT_MIN_POSITION_MM, LIFT_MAX_POSITION_MM)
        if (lastSentLiftPositionMm.isNaN() || abs(liftTargetPositionMm - lastSentLiftPositionMm) >= 0.05f) {
            sendStepperPosition(liftMotorId, liftTargetPositionMm, liftSpeedRpm)
            lastSentLiftPositionMm = liftTargetPositionMm
        }
    }

    private fun mapChannelToRange(
        value: Int,
        inputStart: Int,
        inputEnd: Int,
        outputStart: Float,
        outputEnd: Float
    ): Float {
        val denom = (inputEnd - inputStart).toFloat()
        if (denom == 0f) return outputStart
        val ratio = ((value - inputStart) / denom).coerceIn(0f, 1f)
        return outputStart + ratio * (outputEnd - outputStart)
    }

    private fun sendMotorCommand(code: Int, speedRpm: Float) {
        sendCommandLong(
            command = CMD_MOTOR_CONTROL,
            param1 = code.toFloat(),
            param2 = speedRpm
        )
    }

    private fun sendStepperPosition(motorId: Int, positionMm: Float, speedRpm: Float) {
        sendCommandLong(
            command = CMD_STEPPER_CONTROL,
            param1 = motorId.toFloat(),
            param2 = positionMm,
            param3 = speedRpm
        )
    }

    fun sendStepperHome(motorId: Int = liftMotorId) {
        sendCommandLong(
            command = CMD_STEPPER_HOME,
            param1 = motorId.toFloat()
        )
    }

    fun setLiftMotorId(motorId: Int) {
        liftMotorId = motorId.coerceIn(0, 2)
        runOnUiThread { refreshTopStatus() }
    }

    fun setLiftTargetPosition(positionMm: Float) {
        liftTargetPositionMm = positionMm.coerceIn(LIFT_MIN_POSITION_MM, LIFT_MAX_POSITION_MM)
        sendStepperPosition(liftMotorId, liftTargetPositionMm, liftSpeedRpm)
        lastSentLiftPositionMm = liftTargetPositionMm
        runOnUiThread { refreshTopStatus() }
    }

    fun setLiftSpeed(speedRpm: Float) {
        liftSpeedRpm = speedRpm.coerceIn(0f, LIFT_MAX_RPM)
        runOnUiThread { refreshTopStatus() }
    }

    fun getLiftTargetPositionMm(): Float = liftTargetPositionMm

    fun getLiftSpeedRpm(): Float = liftSpeedRpm

    private fun sendCommandLong(
        command: Int,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f
    ) {
        if (myPipeline == null) return

        val buffer = ByteBuffer.allocate(6 + MAVLINK_COMMAND_LONG_PAYLOAD_LEN + 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0xFE.toByte())
        buffer.put(MAVLINK_COMMAND_LONG_PAYLOAD_LEN.toByte())
        buffer.put((mavlinkSeq++ and 0xFF).toByte())
        buffer.put(255.toByte())
        buffer.put(0.toByte())
        buffer.put(MAVLINK_COMMAND_LONG_MSG_ID.toByte())
        buffer.putFloat(param1)
        buffer.putFloat(param2)
        buffer.putFloat(param3)
        buffer.putFloat(param4)
        buffer.putFloat(param5)
        buffer.putFloat(param6)
        buffer.putFloat(param7)
        buffer.putShort(command.toShort())
        buffer.put(1.toByte())
        buffer.put(1.toByte())
        buffer.put(0.toByte())

        val bytes = buffer.array()
        val crc = calculateMavlinkCRC(bytes, bytes.size - 2, MAVLINK_COMMAND_LONG_CRC_EXTRA)
        buffer.putShort(crc.toShort())
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

    fun updateRobotStatus(
        batteryVol: Float,
        batteryPct: Int,
        currentA: Float,
        pitchAngle: Float,
        pingMs: Int,
        tempOdd: Float,
        tempEven: Float,
        wireDiameter: Float
    ) {
        this.batteryVol = batteryVol
        this.batteryPct = batteryPct
        this.currentA = currentA
        this.pitchAngle = pitchAngle
        this.pingMs = pingMs
        this.tempOdd = tempOdd
        this.tempEven = tempEven
        this.wireDiameter = wireDiameter
        runOnUiThread { refreshTopStatus() }
    }

    private fun refreshTopStatus() {
        val heartbeatText = when {
            lastHeartbeatAckTime == 0L -> "等待"
            System.currentTimeMillis() - lastHeartbeatAckTime > HEARTBEAT_TIMEOUT_MS -> "超时"
            else -> "正常"
        }
        val motorText = when (motorCommand) {
            MOTOR_FORWARD -> "前进 ${motorSpeedRpm.roundToInt()}rpm"
            MOTOR_BACKWARD -> "后退 ${motorSpeedRpm.roundToInt()}rpm"
            else -> "停止"
        }
        tvTopStatus.text = String.format(
            Locale.getDefault(),
            "KR-ZY03 | 心跳:%s | 电机:%s | 升降目标:%.1fmm | 升降速度:%drpm",
            heartbeatText,
            motorText,
            liftTargetPositionMm,
            liftSpeedRpm.roundToInt()
        )
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (currentFragment is DetectFragment) {
            currentFragment.updateLiftStatus(liftTargetPositionMm, liftSpeedRpm)
        }
    }

    private fun handleMotorTelemetry(params: FloatArray) {
        motorTelemetryVoltage = params[0]
        driverTemp1 = params[1]
        motorTemp1 = params[2]
        driverTemp2 = params[3]
        motorTemp2 = params[4]
        lastMotorTelemetryTime = System.currentTimeMillis()
        runOnUiThread { refreshMotorTelemetryStatus() }
    }

    private fun handleVoltageWarning(params: FloatArray) {
        motorTelemetryVoltage = params[0]
        voltageWarningLevel = params[1].roundToInt().coerceIn(0, 2)
        lastVoltageWarningTime = System.currentTimeMillis()
        runOnUiThread { refreshMotorTelemetryStatus() }
    }

    private fun refreshMotorTelemetryStatus() {
        val telemetryOnline = lastMotorTelemetryTime > 0L &&
            System.currentTimeMillis() - lastMotorTelemetryTime <= MOTOR_TELEMETRY_TIMEOUT_MS
        val voltageOnline = !motorTelemetryVoltage.isNaN() && (
            telemetryOnline ||
                lastVoltageWarningTime > 0L &&
                System.currentTimeMillis() - lastVoltageWarningTime <= MOTOR_TELEMETRY_TIMEOUT_MS
            )

        if (voltageOnline) {
            setTelemetryField(
                tvMotorVoltage,
                "电压",
                motorTelemetryVoltage,
                "V",
                voltageColor(),
                voltageWarningLevel >= 2
            )
        } else {
            setTelemetryField(tvMotorVoltage, "电压", null, "V", Color.parseColor("#90A4AE"), false)
        }

        if (!telemetryOnline) {
            setTelemetryField(tvDriverTemp1, "驱动1", null, "℃", Color.parseColor("#90A4AE"), false)
            setTelemetryField(tvMotorTemp1, "电机1", null, "℃", Color.parseColor("#90A4AE"), false)
            setTelemetryField(tvDriverTemp2, "驱动2", null, "℃", Color.parseColor("#90A4AE"), false)
            setTelemetryField(tvMotorTemp2, "电机2", null, "℃", Color.parseColor("#90A4AE"), false)
            return
        }

        setTempField(tvDriverTemp1, "驱动1", driverTemp1, DRIVER_TEMP_WARN_C, DRIVER_TEMP_DANGER_C)
        setTempField(tvMotorTemp1, "电机1", motorTemp1, MOTOR_TEMP_WARN_C, MOTOR_TEMP_DANGER_C)
        setTempField(tvDriverTemp2, "驱动2", driverTemp2, DRIVER_TEMP_WARN_C, DRIVER_TEMP_DANGER_C)
        setTempField(tvMotorTemp2, "电机2", motorTemp2, MOTOR_TEMP_WARN_C, MOTOR_TEMP_DANGER_C)
    }

    private fun setTempField(
        view: TextView,
        label: String,
        value: Float,
        warnThreshold: Float,
        dangerThreshold: Float
    ) {
        val color = when {
            value >= dangerThreshold -> Color.parseColor("#F44336")
            value >= warnThreshold -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#00E5FF")
        }
        setTelemetryField(view, label, value, "℃", color, value >= dangerThreshold)
    }

    private fun setTelemetryField(
        view: TextView,
        label: String,
        value: Float?,
        unit: String,
        color: Int,
        bold: Boolean
    ) {
        val valueText = if (value == null || value.isNaN()) "--" else "%.1f".format(Locale.getDefault(), value)
        view.text = "$label:$valueText$unit"
        view.setTextColor(color)
        view.setTypeface(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun voltageColor(): Int {
        return when (voltageWarningLevel) {
            1 -> Color.parseColor("#FF9800")
            2 -> Color.parseColor("#F44336")
            else -> Color.parseColor("#4CAF50")
        }
    }

    private val mavlinkParser = SimpleMavlinkParser(
        onCommandReceived = commandHandler@{ command, params ->
            when (command) {
                CMD_MOTOR_CONTROL -> {
                    val param1 = params[0]
                    if (abs(param1 - HEARTBEAT_ACK) < 0.1f) {
                        lastHeartbeatAckTime = System.currentTimeMillis()
                        heartbeatTimeoutLogged = false
                        runOnUiThread { refreshTopStatus() }
                        return@commandHandler
                    }

                    runOnUiThread {
                        val waveValue = params[0]
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                        if (currentFragment is DetectFragment) currentFragment.updateGraph(waveValue)
                        if (waveValue >= 95) tvLog.append("High load warning: ${waveValue.toInt()}%\n")

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

                CMD_MOTOR_TELEMETRY -> handleMotorTelemetry(params)
                CMD_VOLTAGE_WARNING -> handleVoltageWarning(params)
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
                if (start == -1) {
                    buffer.clear()
                    return
                }
                if (start > 0) buffer.subList(0, start).clear()
                if (buffer.size < 12) return

                val len = buffer[1].toInt() and 0xFF
                val isV2 = (buffer[0].toInt() and 0xFF) == 0xFD
                val totalLen = (if (isV2) 12 else 8) + len
                if (buffer.size < totalLen) return

                val msgId = if (isV2) {
                    (buffer[7].toInt() and 0xFF) or ((buffer[8].toInt() and 0xFF) shl 8)
                } else {
                    buffer[5].toInt() and 0xFF
                }

                if (msgId == MAVLINK_COMMAND_LONG_MSG_ID && len >= 30) {
                    val offset = if (isV2) 10 else 6
                    val payload = buffer.subList(offset, offset + len).toByteArray()
                    val params = FloatArray(7)
                    for (i in 0..6) {
                        params[i] = ByteBuffer.wrap(payload, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    }
                    val cmd = ByteBuffer.wrap(payload, 28, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    onCommandReceived(cmd, params)
                } else if (msgId == 33) {
                    val offset = if (isV2) 10 else 6
                    val payload = buffer.subList(offset, offset + len).toByteArray()
                    if (payload.size >= 12) {
                        val latInt = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val lonInt = ByteBuffer.wrap(payload, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        onGpsReceived(latInt / 1e7, lonInt / 1e7)
                    }
                }
                buffer.subList(0, totalLen).clear()
            }
        }
    }

    private fun setupVideoTools() {
        findViewById<Button>(R.id.btnRotate)?.setOnClickListener {
            videoView.rotation = (videoView.rotation + 90f) % 360f
        }
        findViewById<Button>(R.id.btnScreenshot)?.setOnClickListener { takeScreenshot() }
        findViewById<Button>(R.id.btnRecord)?.setOnClickListener { view ->
            val btn = view as Button
            isRecording = !isRecording
            if (isRecording) {
                btn.text = "Stop"
                btn.setBackgroundColor(Color.RED)
                tvLog.append("Recording started.\n")
            } else {
                btn.text = "Record"
                btn.setBackgroundColor(Color.parseColor("#1565C0"))
                tvLog.append("Recording stopped.\n")
            }
        }
    }

    private fun takeScreenshot() {
        Toast.makeText(this, "Screenshot is not adapted yet.", Toast.LENGTH_SHORT).show()
    }

    private fun setupVideo() {
        try {
            videoView.url = "rtsp://192.168.144.108:554/stream=0"
            videoView.usingMediaCodec = true
            videoView.start()
        } catch (e: Exception) {
            tvLog.append("Video stream init failed: ${e.message}\n")
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
        val activeColor = Color.parseColor("#2E7D32")
        val inactiveColor = Color.parseColor("#1565C0")
        findViewById<Button>(R.id.btnTabWalk)?.setBackgroundColor(inactiveColor)
        findViewById<Button>(R.id.btnTabDetect)?.setBackgroundColor(inactiveColor)
        findViewById<Button>(R.id.btnTabLocation)?.setBackgroundColor(inactiveColor)
        activeButton?.setBackgroundColor(activeColor)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            android.util.Log.e("JoystickTest", "Physical key code: ${event.keyCode}")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView.stop()
        heartbeatTimer?.cancel()
        joystickTimer?.cancel()
        myPipeline?.let { PipelineManager.disconnectPipeline(it) }
        RCSDKManager.disconnectRC()
    }
}
