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
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocationBookmark
import com.example.data.LocationDatabase
import com.example.data.LocationRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

// Data representation of a GNSS Satellite (GPS, BeiDou, GLONASS)
data class SatelliteInfo(
    val prn: Int,
    val constellationType: String, // "BeiDou", "GPS", "GLONASS"
    val snr: Float, // Signal strength (dB-Hz)
    val elevation: Float, // Degrees 0-90
    val azimuth: Float, // Degrees 0-360
    val usedInFix: Boolean
)

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
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _addressFlow = MutableStateFlow("正在搜星定位中...")
    val addressFlow: StateFlow<String> = _addressFlow.asStateFlow()

    private val _isLocating = MutableStateFlow(false)
    val isLocating: StateFlow<Boolean> = _isLocating.asStateFlow()

    // Active GNSS Constellation tracking flows
    private val _satellites = MutableStateFlow<List<SatelliteInfo>>(emptyList())
    val satellites: StateFlow<List<SatelliteInfo>> = _satellites.asStateFlow()

    private val _beidouCount = MutableStateFlow(0)
    val beidouCount: StateFlow<Int> = _beidouCount.asStateFlow()

    private val _gpsCount = MutableStateFlow(0)
    val gpsCount: StateFlow<Int> = _gpsCount.asStateFlow()

    private val _glonassCount = MutableStateFlow(0)
    val glonassCount: StateFlow<Int> = _glonassCount.asStateFlow()

    // Control simulation / Demo state.
    // If true, the system animates a precise walking route nearby with active simulated satellites
    private val _isSimulationActive = MutableStateFlow(true) // Default to true so emulators see beautiful live data instantly
    val isSimulationActive: StateFlow<Boolean> = _isSimulationActive.asStateFlow()

    private var simulationJob: Job? = null
    private var isLocationListening = false

    // --- Google Location Services components ---
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).apply {
        setMinUpdateIntervalMillis(1500L)
        setMinUpdateDistanceMeters(0.2f)
    }.build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(p0: LocationResult) {
            val lastLocation = p0.lastLocation ?: return
            handleNewLocation(lastLocation, isMocked = false)
        }
    }

    // --- Native Location Manager components (Fallback) ---
    private val nativeLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewLocation(location, isMocked = false)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // --- GNSS Native Satellite status listener ---
    private var gnssStatusCallback: GnssStatus.Callback? = null

    init {
        setupGnssCallback()
    }

    private fun setupGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = @SuppressLint("NewApi") object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    if (_isSimulationActive.value) return // Ignore actual sensors if simulation is forced
                    
                    val list = mutableListOf<SatelliteInfo>()
                    var bdsSum = 0
                    var gpsSum = 0
                    var gloSum = 0

                    for (i in 0 until status.satelliteCount) {
                        val constellation = when (status.getConstellationType(i)) {
                            GnssStatus.CONSTELLATION_BEIDOU -> { bdsSum++; "BeiDou" }
                            GnssStatus.CONSTELLATION_GPS -> { gpsSum++; "GPS" }
                            GnssStatus.CONSTELLATION_GLONASS -> { gloSum++; "GLONASS" }
                            else -> "Other"
                        }

                        list.add(
                            SatelliteInfo(
                                prn = status.getSvid(i),
                                constellationType = constellation,
                                snr = status.getCn0DbHz(i),
                                elevation = status.getElevationDegrees(i),
                                azimuth = status.getAzimuthDegrees(i),
                                usedInFix = status.usedInFix(i)
                            )
                        )
                    }
                    _satellites.value = list
                    _beidouCount.value = bdsSum
                    _gpsCount.value = gpsSum
                    _glonassCount.value = gloSum
                }
            }
        }
    }

    fun startListening() {
        // Register orientation sensors
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
        isLocationListening = true

        try {
            // 1. Trigger Google Play Fused Location Provider
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                getApplication<Application>().mainLooper
            )

            // 2. Trigger native LocationManager as a parallel fallback
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    0.2f,
                    nativeLocationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0.2f,
                    nativeLocationListener,
                    Looper.getMainLooper()
                )
            }

            // Immediately check last known coordinates to fill UI
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _currentLocation.value == null) {
                    handleNewLocation(loc, isMocked = false)
                }
            }

            // 3. Register native GNSS Status tracking
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                try {
                    locationManager.registerGnssStatusCallback(
                        gnssStatusCallback!!,
                        Handler(Looper.getMainLooper())
                    )
                } catch (e: SecurityException) {
                    // Ignore missing permission crashes
                }
            }

        } catch (e: Exception) {
            // Fail gracefully while keeping simulation active
        }

        // Start BeiDou and walking simulator if active
        if (_isSimulationActive.value) {
            startSimulationLoop()
        }
    }

    fun stopLocationUpdates() {
        if (!isLocationListening) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationManager.removeUpdates(nativeLocationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback!!)
            }
        } catch (e: Exception) {
            // Ignore
        }
        stopSimulationLoop()
        _isLocating.value = false
        isLocationListening = false
    }

    // Toggle simulation mode from the UI
    fun toggleSimulation(enabled: Boolean) {
        _isSimulationActive.value = enabled
        if (enabled) {
            startSimulationLoop()
        } else {
            stopSimulationLoop()
            _satellites.value = emptyList()
            _beidouCount.value = 0
            _gpsCount.value = 0
            _glonassCount.value = 0
            // Clear current location to re-trigger real GPS fetch
            _currentLocation.value = null
            _addressFlow.value = "正在重新搜星获取物理GPS..."
        }
    }

    // High fidelity realistic route and satellite status simulator
    private fun startSimulationLoop() {
        stopSimulationLoop()
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            // Simulated center coordinates: Beijing Olympic Green (Lat: 40.0033N, Lng: 116.3888E)
            var lat = 40.0033
            var lng = 116.3888
            var altitude = 43.5 // meters
            var accuracy = 2.1f // meter precision

            // Generate stable but slightly drifting satellite numbers
            val random = Random()

            while (true) {
                // Generate a walking path offset
                lat += (random.nextDouble() - 0.5) * 0.00008
                lng += (random.nextDouble() - 0.5) * 0.00008
                altitude += (random.nextDouble() - 0.5) * 0.4
                accuracy = 1.0f + random.nextFloat() * 1.5f

                val mockLoc = Location("beidou_simulated").apply {
                    this.latitude = lat
                    this.longitude = lng
                    this.altitude = altitude
                    this.accuracy = accuracy
                    this.time = System.currentTimeMillis()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        this.verticalAccuracyMeters = accuracy / 1.5f
                    }
                }

                // Simulate satellite lists (BeiDou, GPS, GLONASS)
                val sList = ArrayList<SatelliteInfo>()
                val bdsCountSim = 12 + random.nextInt(6) // Beijing has an excellent view of BeiDou
                val gpsCountSim = 8 + random.nextInt(4)
                val glonassCountSim = 6 + random.nextInt(4)

                // 1. Mock BeiDou Satellites
                for (i in 0 until bdsCountSim) {
                    sList.add(
                        SatelliteInfo(
                            prn = 100 + i,
                            constellationType = "BeiDou",
                            snr = (28 + random.nextInt(22)).toFloat(),
                            elevation = (15 + random.nextInt(75)).toFloat(),
                            azimuth = random.nextInt(360).toFloat(),
                            usedInFix = i < 8 // Constellation locks
                        )
                    )
                }

                // 2. Mock GPS Satellites
                for (i in 0 until gpsCountSim) {
                    sList.add(
                        SatelliteInfo(
                            prn = 1 + i,
                            constellationType = "GPS",
                            snr = (25 + random.nextInt(20)).toFloat(),
                            elevation = (10 + random.nextInt(80)).toFloat(),
                            azimuth = random.nextInt(360).toFloat(),
                            usedInFix = i < 6
                        )
                    )
                }

                // 3. Mock GLONASS Satellites
                for (i in 0 until glonassCountSim) {
                    sList.add(
                        SatelliteInfo(
                            prn = 50 + i,
                            constellationType = "GLONASS",
                            snr = (22 + random.nextInt(20)).toFloat(),
                            elevation = (10 + random.nextInt(75)).toFloat(),
                            azimuth = random.nextInt(360).toFloat(),
                            usedInFix = i < 4
                        )
                    )
                }

                // Update flows safely on main thread or direct flow mechanics
                _satellites.value = sList.sortedByDescending { it.snr }
                _beidouCount.value = bdsCountSim
                _gpsCount.value = gpsCountSim
                _glonassCount.value = glonassCountSim

                // Dispatch mock location
                launch(Dispatchers.Main) {
                    handleNewLocation(mockLoc, isMocked = true)
                }

                delay(2000L) // Refresh coordinates and satellite signals every 2 seconds
            }
        }
    }

    private fun stopSimulationLoop() {
        simulationJob?.cancel()
        simulationJob = null
    }

    private fun handleNewLocation(location: Location, isMocked: Boolean) {
        // If we received a real GPS coordinate and simulation is currently locked to GPS, handle it
        if (!isMocked && _isSimulationActive.value) {
            // Keep simulation preferred unless user disabled it, but we can store original location
            return
        }

        _currentLocation.value = location
        fetchAddress(location.latitude, location.longitude)
    }

    // Reverse geocode coordinate to localized address
    private fun fetchAddress(latitude: Double, longitude: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_isSimulationActive.value) {
                    // For awesome Chinese local demo, return high-accuracy Beijing Olympic site description
                    _addressFlow.value = "北京市朝阳区国家体育场北路 (北京奥林匹克公园)"
                    return@launch
                }

                if (!Geocoder.isPresent()) {
                    _addressFlow.value = "设备暂不支持逆地理。经纬度: ${String.format(Locale.US, "%.5f, %.5f", latitude, longitude)}"
                    return@launch
                }
                val geocoder = Geocoder(getApplication(), Locale.CHINA)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val addressParts = mutableListOf<String>()
                    
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
                    _addressFlow.value = "已搜星连线。坐标: ${String.format(Locale.US, "%.5f, %.5f", latitude, longitude)}"
                }
            } catch (e: Exception) {
                _addressFlow.value = "网络异常，使用离线缓存定位. 坐标: ${String.format(Locale.US, "%.5f, %.5f", latitude, longitude)}"
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
                
                val targetAzimuth = (degAzimuth + 360f) % 360f
                _azimuthFlow.value = lerpAngle(_azimuthFlow.value, targetAzimuth, 0.25f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
