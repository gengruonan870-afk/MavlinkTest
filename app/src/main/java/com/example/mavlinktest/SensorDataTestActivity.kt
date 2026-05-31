package com.example.mavlinktest

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class SensorDataTestActivity : AppCompatActivity() {

    private data class DamageRecord(
        val collectionNumber: String,
        val positionMeter: String,
        val position: Double?,
        val damageType: Int,
        val value: Double?,
        val detectionTime: String
    )

    private enum class SortKey {
        TIME, COLLECTION, POSITION_METER, POSITION, DAMAGE_TYPE, VALUE
    }

    companion object {
        private const val BASE_URL = "http://36.133.42.8:9010"
        private const val USERNAME = "diankeyuan"
        private const val PASSWORD = "123456"
        private const val TIMEOUT_MS = 15000
    }

    private lateinit var etSerial: EditText
    private lateinit var btnAuth: Button
    private lateinit var btnFetch: Button
    private lateinit var btnShowChart: Button
    private lateinit var btnShowResults: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvChartInfo: TextView
    private lateinit var chartPanel: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private lateinit var chartView: SensorLineChartView
    private lateinit var chartSeekBar: SeekBar
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDamageInfo: TextView
    private lateinit var resultTable: TableLayout
    private lateinit var resultScroll: ScrollView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var accessToken: String? = null
    private var damageRecords = mutableListOf<DamageRecord>()
    private var currentSortKey = SortKey.TIME
    private var sortDescending = true
    private var syncingSeekBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor_data_test)

        bindViews()
        setupActions()
        showChartPanel()
        authenticate()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun bindViews() {
        etSerial = findViewById(R.id.etSensorSerial)
        btnAuth = findViewById(R.id.btnSensorAuth)
        btnFetch = findViewById(R.id.btnSensorFetch)
        btnShowChart = findViewById(R.id.btnShowChart)
        btnShowResults = findViewById(R.id.btnShowResults)
        tvStatus = findViewById(R.id.tvSensorStatus)
        tvChartInfo = findViewById(R.id.tvChartInfo)
        chartPanel = findViewById(R.id.panelChart)
        resultPanel = findViewById(R.id.panelResults)
        chartView = findViewById(R.id.sensorChartView)
        chartSeekBar = findViewById(R.id.seekChartPan)
        progressBar = findViewById(R.id.progressSensor)
        tvDamageInfo = findViewById(R.id.tvDamageInfo)
        resultTable = findViewById(R.id.tableDamageResults)
        resultScroll = findViewById(R.id.scrollResults)
    }

    private fun setupActions() {
        btnAuth.setOnClickListener { authenticate() }
        btnFetch.setOnClickListener { fetchAllData() }
        btnShowChart.setOnClickListener { showChartPanel() }
        btnShowResults.setOnClickListener { showResultPanel() }
        renderDamageTable()
        chartView.onViewportChanged = { ratio ->
            syncingSeekBar = true
            chartSeekBar.progress = (ratio * chartSeekBar.max).toInt().coerceIn(0, chartSeekBar.max)
            syncingSeekBar = false
        }
        chartSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !syncingSeekBar) {
                    chartView.setPanRatio(progress / chartSeekBar.max.toFloat())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val legendIds = intArrayOf(
            R.id.cbSensor1,
            R.id.cbSensor2,
            R.id.cbSensor3,
            R.id.cbSensor4,
            R.id.cbSensor5,
            R.id.cbSensor6
        )
        legendIds.forEachIndexed { index, id ->
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, isChecked ->
                chartView.setChannelVisible(index, isChecked)
            }
        }
    }

    private fun authenticate() {
        setLoading(true, "正在认证...")
        executor.execute {
            try {
                val body = JSONObject()
                    .put("username", USERNAME)
                    .put("pwd", PASSWORD)
                    .toString()
                val response = requestText("$BASE_URL/auth/token", "POST", body, null)
                val json = JSONObject(response)
                val token = json.optJSONObject("data")?.optString("accessToken").orEmpty()
                if (json.optInt("code") == 200 && token.isNotBlank()) {
                    accessToken = token
                    postUi {
                        setLoading(false, "认证成功")
                        tvStatus.text = "状态: 已认证"
                    }
                } else {
                    postUi { setLoading(false, "认证失败: ${json.optString("msg", "未知错误")}") }
                }
            } catch (e: Exception) {
                postUi { setLoading(false, "认证异常: ${e.message ?: "未知错误"}") }
            }
        }
    }

    private fun fetchAllData() {
        val serial = etSerial.text.toString().trim()
        if (serial.isBlank()) {
            Toast.makeText(this, "请输入检测编号", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true, "正在拉取 $serial 的数据...")
        executor.execute {
            try {
                val token = ensureToken()
                val rawText = requestText("$BASE_URL/api/download/$serial", "GET", null, token)
                val samples = parseRawCsv(rawText)
                val damageBody = JSONObject().put("serial", serial).toString()
                val damageText = requestText("$BASE_URL/api/damageResult", "POST", damageBody, token)
                val records = parseDamageResults(damageText)

                postUi {
                    chartView.setSamples(samples)
                    chartSeekBar.progress = 0
                    tvChartInfo.text = "原始数据: ${samples.size} 行，六路传感器"
                    damageRecords = records.toMutableList()
                    tvDamageInfo.text = "损伤结果: 共 ${records.size} 条"
                    renderDamageTable()
                    setLoading(false, "数据拉取完成: 曲线 ${samples.size} 行，损伤 ${records.size} 条")
                    showChartPanel()
                }
            } catch (e: Exception) {
                postUi { setLoading(false, "拉取失败: ${e.message ?: "未知错误"}") }
            }
        }
    }

    private fun ensureToken(): String {
        accessToken?.takeIf { it.isNotBlank() }?.let { return it }
        val body = JSONObject()
            .put("username", USERNAME)
            .put("pwd", PASSWORD)
            .toString()
        val response = requestText("$BASE_URL/auth/token", "POST", body, null)
        val json = JSONObject(response)
        val token = json.optJSONObject("data")?.optString("accessToken").orEmpty()
        if (json.optInt("code") != 200 || token.isBlank()) {
            throw IllegalStateException("认证失败")
        }
        accessToken = token
        return token
    }

    private fun requestText(urlText: String, method: String, body: String?, token: String?): String {
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            if (token != null) setRequestProperty("authorization-token", token)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRawCsv(text: String): List<SensorLineChartView.Sample> {
        val samples = mutableListOf<SensorLineChartView.Sample>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 9) {
                    val index = parts[0].trim().toFloatOrNull()
                    val values = FloatArray(6)
                    var ok = index != null
                    for (i in 0 until 6) {
                        val value = parts[i + 2].trim().toFloatOrNull()
                        if (value == null) {
                            ok = false
                            break
                        }
                        values[i] = value
                    }
                    if (ok && index != null) {
                        samples.add(SensorLineChartView.Sample(index, values))
                    }
                }
            }
        if (samples.isEmpty()) throw IllegalStateException("原始 CSV 没有解析到有效数据")
        return samples
    }

    private fun parseDamageResults(text: String): List<DamageRecord> {
        val json = JSONObject(text)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("msg", "损伤结果接口返回失败"))
        }
        val array = json.optJSONArray("data") ?: return emptyList()
        return List(array.length()) { i ->
            val item = array.optJSONObject(i) ?: JSONObject()
            DamageRecord(
                collectionNumber = item.optString("collectionNumber", "-"),
                positionMeter = item.optString("positionMeter", "-"),
                position = item.optNullableDouble("position"),
                damageType = item.optInt("damageType", 0),
                value = item.optNullableDouble("value"),
                detectionTime = item.optString("detectionTime", "-")
            )
        }
    }

    private fun renderDamageTable() {
        resultTable.removeAllViews()
        addHeaderRow()
        if (damageRecords.isEmpty()) {
            addEmptyDamageRow()
            return
        }
        val sorted = damageRecords.sortedWith(compareBy<DamageRecord> { sortValue(it, currentSortKey) })
            .let { if (sortDescending) it.reversed() else it }
        sorted.forEachIndexed { index, record -> addDamageRow(index, record) }
    }

    private fun addHeaderRow() {
        val row = TableRow(this)
        addHeaderCell(row, "检测时间", SortKey.TIME)
        addHeaderCell(row, "批次", SortKey.COLLECTION)
        addHeaderCell(row, "米数位置", SortKey.POSITION_METER)
        addHeaderCell(row, "脉冲计数", SortKey.POSITION)
        addHeaderCell(row, "损伤等级", SortKey.DAMAGE_TYPE)
        addHeaderCell(row, "损伤量(%)", SortKey.VALUE)
        resultTable.addView(row)
    }

    private fun addEmptyDamageRow() {
        val row = TableRow(this).apply {
            setBackgroundColor(Color.rgb(10, 17, 40))
        }
        val tv = TextView(this).apply {
            text = "暂无损伤结果。请先点击“获取数据”；如果获取后仍为空，说明接口当前未返回损伤记录。"
            setTextColor(Color.rgb(136, 204, 255))
            textSize = 12f
            minWidth = 780
            setPadding(12, 18, 12, 18)
        }
        row.addView(tv)
        resultTable.addView(row)
    }

    private fun addHeaderCell(row: TableRow, title: String, sortKey: SortKey) {
        val arrow = if (currentSortKey == sortKey) {
            if (sortDescending) "↓" else "↑"
        } else {
            ""
        }
        val button = Button(this).apply {
            text = "$title$arrow"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(21, 101, 192))
            minWidth = 130
            setOnClickListener {
                if (currentSortKey == sortKey) {
                    sortDescending = !sortDescending
                } else {
                    currentSortKey = sortKey
                    sortDescending = sortKey == SortKey.TIME
                }
                renderDamageTable()
                resultScroll.post { resultScroll.scrollTo(0, 0) }
            }
        }
        row.addView(button)
    }

    private fun addDamageRow(index: Int, record: DamageRecord) {
        val row = TableRow(this).apply {
            setBackgroundColor(if (index % 2 == 0) Color.rgb(10, 17, 40) else Color.rgb(16, 28, 51))
        }
        val color = damageColor(record.damageType)
        addCell(row, record.detectionTime, color)
        addCell(row, record.collectionNumber, color)
        addCell(row, record.positionMeter, color)
        addCell(row, record.position?.formatNumber() ?: "-", color)
        addCell(row, damageTypeText(record.damageType), color)
        addCell(row, record.value?.formatNumber() ?: "-", color)
        resultTable.addView(row)
    }

    private fun addCell(row: TableRow, text: String, color: Int) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            gravity = Gravity.CENTER
            minWidth = 130
            setPadding(8, 10, 8, 10)
        }
        row.addView(tv)
    }

    private fun sortValue(record: DamageRecord, key: SortKey): Comparable<*> {
        return when (key) {
            SortKey.TIME -> record.detectionTime
            SortKey.COLLECTION -> record.collectionNumber
            SortKey.POSITION_METER -> record.positionMeter.replace("m", "").toDoubleOrNull() ?: 0.0
            SortKey.POSITION -> record.position ?: 0.0
            SortKey.DAMAGE_TYPE -> record.damageType
            SortKey.VALUE -> record.value ?: 0.0
        }
    }

    private fun damageTypeText(type: Int): String = when (type) {
        1 -> "轻度"
        2 -> "中度"
        3 -> "重度"
        4 -> "超限"
        else -> "未知"
    }

    private fun damageColor(type: Int): Int = when (type) {
        1 -> Color.rgb(46, 204, 113)
        2 -> Color.rgb(243, 156, 18)
        3 -> Color.rgb(230, 126, 34)
        4 -> Color.rgb(231, 76, 60)
        else -> Color.WHITE
    }

    private fun showChartPanel() {
        chartPanel.visibility = View.VISIBLE
        resultPanel.visibility = View.GONE
        btnShowChart.setBackgroundColor(Color.rgb(46, 125, 50))
        btnShowResults.setBackgroundColor(Color.rgb(21, 101, 192))
    }

    private fun showResultPanel() {
        chartPanel.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
        btnShowChart.setBackgroundColor(Color.rgb(21, 101, 192))
        btnShowResults.setBackgroundColor(Color.rgb(46, 125, 50))
    }

    private fun setLoading(loading: Boolean, message: String) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnAuth.isEnabled = !loading
        btnFetch.isEnabled = !loading
        tvStatus.text = "状态: $message"
    }

    private fun postUi(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getDouble(name) }.getOrNull()
    }

    private fun Double.formatNumber(): String = String.format(Locale.getDefault(), "%.3f", this)
}
