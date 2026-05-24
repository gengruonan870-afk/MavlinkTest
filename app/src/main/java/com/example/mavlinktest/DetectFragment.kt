package com.example.mavlinktest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment

class DetectFragment : Fragment() {
    private var dataGraph: RealtimeGraphView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_detect, container, false)

        dataGraph = view.findViewById(R.id.multiDataGraph)

        // 绑定升降和夹紧按钮
        view.findViewById<Button>(R.id.btnDetectClamp).setOnClickListener {
            Toast.makeText(context, "发送: 夹紧传感器", Toast.LENGTH_SHORT).show()
        }

        return view
    }

    // 这个方法留给 MainActivity 收到串口数据时调用
    fun updateGraph(value: Float) {
        dataGraph?.addDataPoint(value)
    }
}