package com.example.mavlinktest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class WalkFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_walk, container, false)

        val tvCurrentSpeed = view.findViewById<TextView>(R.id.tvCurrentSpeed)

        view.findViewById<Button>(R.id.btnWalkForward).setOnClickListener {
            Toast.makeText(context, "发送: 前进指令", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnWalkBackward).setOnClickListener {
            Toast.makeText(context, "发送: 后退指令", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<SeekBar>(R.id.sbWalkSpeed).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 实时显示当前拖动的速度
                tvCurrentSpeed.text = "当前设定速度: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Toast.makeText(context, "设定速度已下发", Toast.LENGTH_SHORT).show()
            }
        })

        return view
    }
}