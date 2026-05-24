package com.example.mavlinktest // 注意：把这里改成你自己的包名！

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.skydroid.rcsdk.PipelineManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.comm.CommListener
import com.skydroid.rcsdk.common.Uart
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.pipeline.Pipeline

class LoopbackTestActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button

    private var myPipeline: Pipeline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 假设你的布局文件叫 activity_loopback_test (里面只要有个输入框，个发送按钮，一个TextView显示日志就行)
        setContentView(R.layout.activity_loopback_test)

        tvLog = findViewById(R.id.tvLog)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)

        tvLog.text = "初始化中...\n"

        // 1. 初始化云卓 SDK
        RCSDKManager.initSDK(this, object : SDKManagerCallBack {
            override fun onRcConnected() {
                runOnUiThread { tvLog.append("✅ 遥控器底层握手成功！\n") }
                setupPipeline() // 握手成功后，去建立无线传输管道
            }
            override fun onRcConnectFail(e: SkyException?) {
                runOnUiThread { tvLog.append("❌ 遥控器握手失败: $e\n") }
            }
            override fun onRcDisconnect() {}
        })
        RCSDKManager.setMainThreadCallBack(true)
        RCSDKManager.connectToRC()

        btnSend.setOnClickListener {
            val textToSend = etInput.text.toString()
            if (textToSend.isNotEmpty()) {
                // 用 ?. 来安全调用，意思是“如果不为空，就发送”
                myPipeline?.writeData(textToSend.toByteArray())
                tvLog.append("⬆️ 发送: $textToSend\n")
            } else {
                Toast.makeText(this, "输入为空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. 建立与接收机的无线管道
    private fun setupPipeline() {
        myPipeline = PipelineManager.createPipeline(Uart.UART0)

        myPipeline?.onCommListener = object : CommListener {
            override fun onConnectSuccess() {
                runOnUiThread { tvLog.append("📡 无线管道连接成功！可以发数据了。\n") }
            }

            override fun onConnectFail(e: SkyException) {
                runOnUiThread { tvLog.append("❌ 管道连接失败: $e\n") }
            }

            override fun onDisconnect() {
                runOnUiThread { tvLog.append("⚠️ 管道断开连接\n") }
            }

            override fun onReadData(bytes: ByteArray) {
                // 收到接收机返回的数据了！
                val receivedText = String(bytes)
                runOnUiThread {
                    tvLog.append("⬇️ 收到回环数据: $receivedText\n")
                }
            }
        }

        myPipeline?.let {
            PipelineManager.connectPipeline(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        myPipeline?.let { PipelineManager.disconnectPipeline(it) }
        RCSDKManager.disconnectRC()
    }
}