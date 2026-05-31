package com.example.mavlinktest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.util.Locale
import kotlin.math.roundToInt

class DetectFragment : Fragment() {
    private var dataGraph: RealtimeGraphView? = null
    private var tvLiftPositionStatus: TextView? = null
    private var tvLiftSpeedStatus: TextView? = null
    private var etLiftTargetPosition: EditText? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_detect, container, false)

        dataGraph = view.findViewById(R.id.multiDataGraph)
        tvLiftPositionStatus = view.findViewById(R.id.tvLiftPositionStatus)
        tvLiftSpeedStatus = view.findViewById(R.id.tvLiftSpeedStatus)
        etLiftTargetPosition = view.findViewById(R.id.etLiftTargetPosition)

        view.findViewById<Button>(R.id.btnLiftSendPosition).setOnClickListener {
            val target = etLiftTargetPosition?.text?.toString()?.toFloatOrNull()
            if (target == null) {
                Toast.makeText(context, "请输入目标位置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            (activity as? MainActivity)?.setLiftTargetPosition(target)
            Toast.makeText(context, "已发送目标位置", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnLiftHome).setOnClickListener {
            (activity as? MainActivity)?.sendStepperHome()
            Toast.makeText(context, "已发送回零命令", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnDetectClamp).setOnClickListener {
            Toast.makeText(context, "夹紧功能待接入", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnDetectRelease).setOnClickListener {
            Toast.makeText(context, "松开功能待接入", Toast.LENGTH_SHORT).show()
        }

        refreshFromActivity()
        return view
    }

    override fun onResume() {
        super.onResume()
        refreshFromActivity()
    }

    fun updateGraph(value: Float) {
        dataGraph?.addDataPoint(value)
    }

    fun updateLiftStatus(targetPositionMm: Float, speedRpm: Float) {
        tvLiftPositionStatus?.text = String.format(
            Locale.getDefault(),
            "目标位置: %.1f mm",
            targetPositionMm
        )
        tvLiftSpeedStatus?.text = String.format(
            Locale.getDefault(),
            "升降速度: %d RPM",
            speedRpm.roundToInt()
        )
        if (!etLiftTargetPosition.hasFocusSafe()) {
            etLiftTargetPosition?.setText(String.format(Locale.getDefault(), "%.1f", targetPositionMm))
        }
    }

    private fun refreshFromActivity() {
        val mainActivity = activity as? MainActivity ?: return
        updateLiftStatus(
            targetPositionMm = mainActivity.getLiftTargetPositionMm(),
            speedRpm = mainActivity.getLiftSpeedRpm()
        )
    }

    private fun EditText?.hasFocusSafe(): Boolean = this?.hasFocus() == true
}
