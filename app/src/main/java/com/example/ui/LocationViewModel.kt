package com.example.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocationBookmark
import com.example.data.LocationDatabase
import com.example.data.LocationRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class LocationViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val database = LocationDatabase.getDatabase(application)
    private val repository = LocationRepository(database.locationBookmarkDao())

    // Bookmarks Flow from Database
    val bookmarks: StateFlow<List<LocationBookmark>> = repository.allBookmarks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Sensor Management
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    // Compass flows
    private val _azimuthFlow = MutableStateFlow(0f)
    val azimuthFlow: StateFlow<Float> = _azimuthFlow.asStateFlow()

    // GPS location flows
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _addressFlow = MutableStateFlow("正在定位中...")
    val addressFlow: StateFlow<String> = _addressFlow.asStateFlow()

    private val _isLocating = MutableStateFlow(false)
    val isLocating: StateFlow<Boolean> = _isLocating.asStateFlow()

    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L).apply {
        setMinUpdateIntervalMillis(2000L)
        setMinUpdateDistanceMeters(0.5f)
    }.build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(p0: LocationResult) {
            val lastLocation = p0.lastLocation ?: return
            _currentLocation.value = lastLocation
            fetchAddress(lastLocation.latitude, lastLocation.longitude)
        }
    }

    private var isLocationListening = false

    fun startListening() {
        // Register sensors
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isLocationListening) return
        _isLocating.value = true
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                getApplication<Application>().mainLooper
            )
            // Immediately request last known location for rapid display
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _currentLocation.value == null) {
                    _currentLocation.value = loc
                    fetchAddress(loc.latitude, loc.longitude)
                }
            }
            isLocationListening = true
        } catch (e: Exception) {
            _addressFlow.value = "无法获取GPS: 没有定位权限或未开启定位"
            _isLocating.value = false
        }
    }

    fun stopLocationUpdates() {
        if (!isLocationListening) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        _isLocating.value = false
        isLocationListening = false
    }

    // Reverse geocode coordinate to localized address
    private fun fetchAddress(latitude: Double, longitude: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) {
                    _addressFlow.value = "设备暂不支持逆地理编码。经纬度: ${String.format(Locale.US, "%.5f, %.5f", latitude, longitude)}"
                    return@launch
                }
                val geocoder = Geocoder(getApplication(), Locale.CHINA)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val addressParts = mutableListOf<String>()
                    
                    // Construct readable address
                    address.adminArea?.let { addressParts.add(it) }
                    address.locality?.let { addressParts.add(it) }
                    address.subLocality?.let { addressParts.add(it) }
                    address.thoroughfare?.let { addressParts.add(it) }
                    address.featureName?.let { 
                        if (it != address.thoroughfare) addressParts.add(it) 
                    }
                    
                    var resolved = addressParts.joinToString("")
                    if (resolved.isEmpty()) {
                        resolved = address.getAddressLine(0) ?: "未知具体位置"
                    }
                    _addressFlow.value = resolved
                } else {
                    _addressFlow.value = "未知位置 (附近无明显地标)"
                }
            } catch (e: Exception) {
                // Return gracefully without blocking or crashing. This represents high quality offline behavior.
                _addressFlow.value = "未联网，显示离线经纬度"
            } finally {
                _isLocating.value = false
            }
        }
    }

    // Bookmarking Actions
    fun saveBookmark(name: String) {
        val loc = _currentLocation.value
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0
        val alt = loc?.altitude ?: 0.0
        val acc = loc?.accuracy ?: 0f
        val dir = _azimuthFlow.value
        val addr = if (loc != null) _addressFlow.value else "无GPS坐标信号"

        viewModelScope.launch(Dispatchers.IO) {
            val resolvedName = name.ifBlank { "我的位置 (${String.format(Locale.US, "%.4f, %.4f", lat, lng)})" }
            repository.insertBookmark(
                LocationBookmark(
                    name = resolvedName,
                    latitude = lat,
                    longitude = lng,
                    altitude = alt,
                    accuracy = acc,
                    directionAngle = dir,
                    address = addr
                )
            )
        }
    }

    fun deleteBookmark(bookmark: LocationBookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBookmark(bookmark)
        }
    }

    fun updateBookmarkName(bookmark: LocationBookmark, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateBookmark(bookmark.copy(name = newName))
        }
    }

    // Compass calculation interface methods
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, event.values.size)
            hasGravity = true
        }
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
            hasGeomagnetic = true
        }

        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val radAzimuth = orientation[0]
                val degAzimuth = Math.toDegrees(radAzimuth.toDouble()).toFloat()
                
                // Normalizing to 0..360 range
                val targetAzimuth = (degAzimuth + 360f) % 360f
                
                // Smooth interpolation to prevent jittering, using lerpAngle
                _azimuthFlow.value = lerpAngle(_azimuthFlow.value, targetAzimuth, 0.25f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Can be logged or ignored for UI updates
    }

    // Utility for smooth angular interpolation
    private fun lerpAngle(from: Float, to: Float, step: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return (from + diff * step + 360f) % 360f
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
        stopLocationUpdates()
    }
}
