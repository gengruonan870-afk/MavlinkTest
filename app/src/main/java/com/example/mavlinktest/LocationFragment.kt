package com.example.mavlinktest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapUtils
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions

class LocationFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var tvLocationInfo: TextView // 对应你截图里的那个半透明黑框 TextView
    private var aMap: AMap? = null

    // 位置和图标
    private var rcLocation: LatLng? = null
    private var uavLocation: LatLng? = null
    private var rcMarker: Marker? = null
    private var uavMarker: Marker? = null

    // 遥控器自身定位客户端
    private var locationClient: AMapLocationClient? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // 假设你的 xml 文件名是 fragment_location
        val view = inflater.inflate(R.layout.fragment_location, container, false)

        mapView = view.findViewById(R.id.mapView) // 确保 XML 里 MapView 的 id 是 mapView
        // 如果你 XML 里的 TextView ID 不一样，请在这里修改
        tvLocationInfo = view.findViewById(R.id.tvLocationInfo)
        // 给黑框加上点击事件：一键唤起系统导航
        tvLocationInfo.setOnClickListener {
            val uav = uavLocation
            if (uav != null) {
                try {
                    // 尝试直接唤起高德地图 App 进行步行导航 (t=4 代表步行)
                    val uri = android.net.Uri.parse("amapuri://route/plan/?dlat=${uav.latitude}&dlon=${uav.longitude}&dname=丢失的无人机&dev=0&t=4")
                    val intent = android.content.Intent("android.intent.action.VIEW", uri)
                    intent.setPackage("com.autonavi.minimap")
                    startActivity(intent)
                } catch (e: Exception) {
                    // 如果遥控器里没装高德 App，就调用系统默认地图打开该坐标
                    val uri = android.net.Uri.parse("geo:${uav.latitude},${uav.longitude}?q=${uav.latitude},${uav.longitude}(丢失的无人机)")
                    val intent = android.content.Intent("android.intent.action.VIEW", uri)
                    startActivity(intent)
                }
            } else {
                android.widget.Toast.makeText(requireContext(), "还没收到无人机位置，无法开启导航！", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        mapView.onCreate(savedInstanceState)
        if (aMap == null) {
            aMap = mapView.map
            aMap?.uiSettings?.isZoomControlsEnabled = true
        }

        // 检查并申请定位权限
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        } else {
            startRcLocation()
        }

        return view
    }

    // 启动遥控器自身定位
    private fun startRcLocation() {
        try {
            AMapLocationClient.updatePrivacyShow(requireContext(), true, true)
            AMapLocationClient.updatePrivacyAgree(requireContext(), true)

            locationClient = AMapLocationClient(requireContext())
            val locationOption = AMapLocationClientOption()
            locationOption.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            locationOption.interval = 2000 // 每2秒获取一次遥控器位置

            locationClient?.setLocationOption(locationOption)
            locationClient?.setLocationListener(AMapLocationListener { aMapLocation ->
                if (aMapLocation != null && aMapLocation.errorCode == 0) {
                    val lat = aMapLocation.latitude
                    val lon = aMapLocation.longitude
                    rcLocation = LatLng(lat, lon)

                    // 第一次定位成功时，把地图视角移到遥控器位置
                    if (rcMarker == null) {
                        aMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(rcLocation, 17f))
                    }

                    updateMapDisplay()
                }
            })
            locationClient?.startLocation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ⚡ 这个方法由 MainActivity 调用，传入无人机实时经纬度
    fun updateUavPosition(lat: Double, lon: Double) {
        uavLocation = LatLng(lat, lon)

        // 第一次收到无人机数据时，自动把视角切过去
        if (uavMarker == null) {
            aMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(uavLocation, 18f))
        }
        updateMapDisplay()
    }

    // 统一更新地图上的图标和计算距离
    private fun updateMapDisplay() {
        // 1. 更新遥控器位置 (蓝点)
        rcLocation?.let {
            if (rcMarker == null) {
                rcMarker = aMap?.addMarker(MarkerOptions().position(it).title("遥控器 (我)").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)))
            } else {
                rcMarker?.position = it
            }
        }

        // 2. 更新无人机位置 (红点)
        uavLocation?.let {
            if (uavMarker == null) {
                uavMarker = aMap?.addMarker(MarkerOptions().position(it).title("无人机 (KR-ZY03)").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
            } else {
                uavMarker?.position = it
            }
        }

        // 3. 计算距离并更新文字
        if (rcLocation != null && uavLocation != null) {
            // 使用高德地图 API 计算两点直线距离（单位：米）
            val distance = AMapUtils.calculateLineDistance(rcLocation, uavLocation)

            // 可选：在地图上画一条连接两点的红线
            aMap?.clear() // 先清空旧线
            rcMarker = aMap?.addMarker(MarkerOptions().position(rcLocation).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)))
            uavMarker = aMap?.addMarker(MarkerOptions().position(uavLocation).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
            aMap?.addPolyline(PolylineOptions().add(rcLocation, uavLocation).width(5f).color(Color.RED))

            tvLocationInfo.text = "距遥控器: ${String.format("%.1f", distance)} m\n无人机 Lat: ${String.format("%.5f", uavLocation?.latitude)}, Lon: ${String.format("%.5f", uavLocation?.longitude)}"
        } else if (uavLocation != null) {
            tvLocationInfo.text = "距遥控器: 定位中...\n无人机 Lat: ${String.format("%.5f", uavLocation?.latitude)}, Lon: ${String.format("%.5f", uavLocation?.longitude)}"
        }
    }

    // 管理 MapView 的生命周期
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy(); locationClient?.onDestroy() }
}