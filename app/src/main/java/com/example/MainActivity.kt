package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.LocationBookmark
import com.example.ui.LocationViewModel
import com.example.ui.SatelliteInfo
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel: LocationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold")
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startListening()
        // If permission is already granted, start GPS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopListening()
        viewModel.stopLocationUpdates()
    }
}

// Visual theme definitions
val ObsidianDarkBg = Color(0xFF090D16)
val PanelDarkBg = Color(0xFF131B2E)
val AccentOrange = Color(0xFFFF9800)
val NeonCyan = Color(0xFF00E5FF)
val TextGray = Color(0xFF94A3B8)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: LocationViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permissionState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    // Select view tab: 0 = Compass & GPS, 1 = Bookmarks
    var selectedTab by remember { mutableStateOf(0) }

    // Synchronize permission status with location listener
    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted) {
            viewModel.startLocationUpdates()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianDarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "定位图标",
                        tint = AccentOrange,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("app_logo_icon")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "指南针定位工具",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }

                // GPS pulse indicator
                val isLocating by viewModel.isLocating.collectAsStateWithLifecycle()
                Surface(
                    color = if (permissionState.allPermissionsGranted) Color(0xFF0D5c3a) else Color(0xFF5a1D1D),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val pulseTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseAlpha by pulseTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (permissionState.allPermissionsGranted) {
                                        Color(0xFF2ECC71).copy(alpha = if (isLocating) pulseAlpha else 1f)
                                    } else {
                                        Color.Red
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (permissionState.allPermissionsGranted) "GPS 已就绪" else "缺少定位权限",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Tab bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = ObsidianDarkBg,
                contentColor = AccentOrange,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AccentOrange,
                        height = 3.dp
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("tab_compass")
                ) {
                    Column(modifier = Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "罗盘",
                            tint = if (selectedTab == 0) AccentOrange else Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "指南针和定位",
                            color = if (selectedTab == 0) AccentOrange else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("tab_bookmarks")
                ) {
                    Column(modifier = Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
                        BadgedBox(badge = {
                            if (bookmarks.isNotEmpty()) {
                                Badge(containerColor = AccentOrange) {
                                    Text(text = bookmarks.size.toString(), color = Color.White)
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Bookmarks,
                                contentDescription = "书签",
                                tint = if (selectedTab == 1) AccentOrange else Color.Gray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "本地收藏书签",
                            color = if (selectedTab == 1) AccentOrange else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!permissionState.allPermissionsGranted) {
                PermissionRequiredScreen(permissionState)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (selectedTab == 0) {
                        CompassTabContent(viewModel = viewModel)
                    } else {
                        BookmarksTabContent(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequiredScreen(permissionState: MultiplePermissionsState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.GpsOff,
            contentDescription = "未授权",
            tint = Color.Gray,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "定位服务未开启",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "此工具需要利用手机的麦克风与GPS传感器来测量方位、纬度、经度和海拔高度。请授予应用定位权限以支持实时显示。",
            color = TextGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(30.dp))
        Button(
            onClick = { permissionState.launchMultiplePermissionRequest() },
            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .height(48.dp)
                .fillMaxWidth(0.7f)
                .testTag("request_permission_button")
        ) {
            Text(text = "授权获取定位", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CompassTabContent(viewModel: LocationViewModel) {
    val context = LocalContext.current
    val azimuth by viewModel.azimuthFlow.collectAsStateWithLifecycle()
    val location by viewModel.currentLocation.collectAsStateWithLifecycle()
    val address by viewModel.addressFlow.collectAsStateWithLifecycle()

    // BeiDou / GNSS flows
    val isSimulationActive by viewModel.isSimulationActive.collectAsStateWithLifecycle()
    val satellites by viewModel.satellites.collectAsStateWithLifecycle()
    val beidouCount by viewModel.beidouCount.collectAsStateWithLifecycle()
    val gpsCount by viewModel.gpsCount.collectAsStateWithLifecycle()
    val glonassCount by viewModel.glonassCount.collectAsStateWithLifecycle()

    var showSaveDialog by remember { mutableStateOf(false) }
    var saveNameInput by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Compass Graphic Drawing in Canvas
        Box(
            modifier = Modifier
                .size(280.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            // Ambient outer glow for high tech look
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawCircle(
                            Brush.radialGradient(
                                colors = listOf(NeonCyan.copy(alpha = 0.08f), Color.Transparent),
                                center = center,
                                radius = size.minDimension / 1.5f
                            )
                        )
                    }
            )

            CompassDial(azimuth = azimuth)
            
            // Numeric reading overlaid beautifully at center/bottom of the circle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.offset(y = 4.dp)
            ) {
                Text(
                    text = "${azimuth.toInt()}°",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = getChineseBearingDirection(azimuth),
                    color = AccentOrange,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- NEW: BeiDou Assistance Control Panel ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = PanelDarkBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (isSimulationActive) NeonCyan.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "辅助定位",
                            tint = if (isSimulationActive) NeonCyan else AccentOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "北斗高精度辅助定位",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isSimulationActive) "已开启数字差分RTK厘米级解算" else "物理硬件直连侦听中",
                                color = if (isSimulationActive) NeonCyan else TextGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = isSimulationActive,
                        onCheckedChange = { viewModel.toggleSimulation(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color.DarkGray
                        ),
                        modifier = Modifier.testTag("beidou_simulation_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color.White.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(12.dp))

                // Constellation satellite info badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConstellationBadge(
                        title = "BDS 北斗",
                        count = beidouCount,
                        color = Color(0xFFFF5722),
                        modifier = Modifier.weight(1f)
                    )
                    ConstellationBadge(
                        title = "GPS 卫星",
                        count = gpsCount,
                        color = Color(0xFF00E5FF),
                        modifier = Modifier.weight(1f)
                    )
                    ConstellationBadge(
                        title = "GLONASS",
                        count = glonassCount,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.weight(1f)
                    )
                }

                // If satellites info present, show visual SNR bars for top signals!
                if (satellites.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "强信号卫星通道载噪比 (CN0)",
                        color = TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        satellites.take(8).forEach { sat ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = sat.snr.toInt().toString(),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .height((sat.snr * 1.0f).dp.coerceIn(4.dp, 40.dp))
                                        .background(
                                            color = when (sat.constellationType) {
                                                "BeiDou" -> Color(0xFFFF5722)
                                                "GPS" -> Color(0xFF00E5FF)
                                                else -> Color(0xFF4CAF50)
                                            },
                                            shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                        )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when (sat.constellationType) {
                                        "BeiDou" -> "B${sat.prn % 100}"
                                        "GPS" -> "G${sat.prn}"
                                        else -> "R${sat.prn}"
                                    },
                                    color = TextGray,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Position Stats Grid card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = PanelDarkBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "卫星精密解算报表",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CoordinateItem(
                        title = "经度 (Longitude)",
                        value = if (location != null) String.format(Locale.US, "%.7f° %s", Math.abs(location!!.longitude), if (location!!.longitude >= 0) "E" else "W") else "--",
                        modifier = Modifier.weight(1f)
                    )
                    CoordinateItem(
                        title = "纬度 (Latitude)",
                        value = if (location != null) String.format(Locale.US, "%.7f° %s", Math.abs(location!!.latitude), if (location!!.latitude >= 0) "N" else "S") else "--",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CoordinateItem(
                        title = "物理及大地高 (Altitude)",
                        value = if (location != null) "${String.format(Locale.US, "%.2f", location!!.altitude)} 米" else "--",
                        modifier = Modifier.weight(1f)
                    )
                    CoordinateItem(
                        title = "三维定位精度 (Accuracy)",
                        value = if (location != null) "± ${String.format(Locale.US, "%.2f", location!!.accuracy)} 米" else "--",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Resolving physical address card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = PanelDarkBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = "地址",
                    tint = NeonCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前估算街道详细地址",
                        color = TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = address,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        if (location != null) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("地址", "$address (经纬度: ${location!!.latitude},${location!!.longitude})")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "经纬度及地址已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "暂无坐标信号可供复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("copy_address_button")
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "复制", tint = TextGray)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Sharing and Bookmarking Button block
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Share button
            Button(
                onClick = {
                    if (location != null) {
                        shareLocation(context, location!!, address, azimuth)
                    } else {
                        Toast.makeText(context, "正在获取GPS卫星信号，请稍等...", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PanelDarkBg),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("share_location_button")
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "分享", tint = AccentOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "分享我的位置", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            // Save bookmark button
            Button(
                onClick = {
                    if (location != null) {
                        // Autofill suggestion with current approximate reverse-engineered address
                        saveNameInput = address.substringBefore(" ").ifBlank { "" }
                        showSaveDialog = true
                    } else {
                        Toast.makeText(context, "无法获取信号，请首先赋予权限并在开阔地带更新位置。", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("add_bookmark_button")
            ) {
                Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = "收藏", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "保存为书签", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Bookmark saving dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(text = "添加位置书签", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(text = "在无网络时也能正常访问此记录。赋予其一个辨识标签：", color = TextGray, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = saveNameInput,
                        onValueChange = { saveNameInput = it },
                        placeholder = { Text(text = "例如: 我的宿营基地, 故宫角楼") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentOrange,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedLabelColor = AccentOrange
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bookmark_name_input")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveBookmark(saveNameInput)
                        showSaveDialog = false
                        Toast.makeText(context, "书签已成功保存至本地数据库", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("save_dialog_confirm")
                ) {
                    Text(text = "确定保存", color = AccentOrange, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(text = "取消", color = Color.Gray)
                }
            },
            containerColor = PanelDarkBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun ConstellationBadge(title: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = TextGray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${count} 颗",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun CoordinateItem(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, color = TextGray, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// Beautiful vector custom drawn compass dial which rotates negative orientation degrees
@Composable
fun CompassDial(azimuth: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("compass_canvas")
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.9f

        // Draw static clock-like instrument guidelines (crosshair lines)
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx()
        )

        // Rotate the entire dial based on azimuth input. 
        // We rotate negative because when device rotates right (bearing increases), the compass dial needs to shift left to keep North pointing forward.
        rotate(degrees = -azimuth, pivot = center) {
            
            // Draw outer Dial Ring
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = radius,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Outer tick marks and numbers
            for (angle in 0 until 360 step 5) {
                val isCardinal = angle % 90 == 0
                val isMajor = angle % 30 == 0
                
                val tickLength = if (isCardinal) 12.dp.toPx() else if (isMajor) 8.dp.toPx() else 4.dp.toPx()
                val strokeWidth = if (isCardinal) 2.5.dp.toPx() else if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                
                val color = when {
                    angle == 0 -> Color.Red // North tick is Red
                    isCardinal -> AccentOrange
                    else -> Color.White.copy(alpha = 0.4f)
                }

                // Adjust angle -90 degrees because 0 degrees on Canvas starts on the right (x-axis),
                // but North is vertically upwards (y-axis)
                val angleRad = Math.toRadians((angle - 90).toDouble()).toFloat()

                val startX = center.x + (radius - tickLength) * cos(angleRad)
                val startY = center.y + (radius - tickLength) * sin(angleRad)
                val endX = center.x + radius * cos(angleRad)
                val endY = center.y + radius * sin(angleRad)

                drawLine(
                    color = color,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = strokeWidth
                )

                // Render number texts for major degrees (N, E, S, W in Chinese, or degree numbers)
                if (isCardinal) {
                    val label = when (angle) {
                        0 -> "北"
                        90 -> "东"
                        180 -> "南"
                        270 -> "西"
                        else -> ""
                    }
                    val labelRadius = radius - 24.dp.toPx()
                    val labelX = center.x + labelRadius * cos(angleRad)
                    val labelY = center.y + labelRadius * sin(angleRad)

                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = if (angle == 0) android.graphics.Color.RED else android.graphics.Color.WHITE
                            textSize = 14.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        }
                        // Center vertically adjust text draw
                        drawText(label, labelX, labelY + paint.textSize / 3, paint)
                    }
                } else if (isMajor) {
                    val labelRadius = radius - 20.dp.toPx()
                    val labelX = center.x + labelRadius * cos(angleRad)
                    val labelY = center.y + labelRadius * sin(angleRad)

                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.GRAY
                            textSize = 8.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        drawText(angle.toString(), labelX, labelY + paint.textSize / 3, paint)
                    }
                }
            }
        }

        // Draw a static, top-locked pointer needle representing current forward heading
        val needleHeadWidth = 10.dp.toPx()
        val needleHeadHeight = 16.dp.toPx()
        
        val needlePath = Path().apply {
            moveTo(center.x, center.y - radius - 8.dp.toPx()) // Tip
            lineTo(center.x - needleHeadWidth / 2, center.y - radius + needleHeadHeight - 8.dp.toPx()) // Left base
            lineTo(center.x + needleHeadWidth / 2, center.y - radius + needleHeadHeight - 8.dp.toPx()) // Right base
            close()
        }
        drawPath(path = needlePath, color = NeonCyan)

        // Draw outer ring border frame
        drawCircle(
            brush = GlowOrangeGradient(center, radius),
            radius = radius + 2.dp.toPx(),
            style = Stroke(width = 0.5.dp.toPx())
        )
    }
}

// Shader helper for dynamic gradient ring
private fun GlowOrangeGradient(center: Offset, radius: Float): Brush {
    return Brush.sweepGradient(
        colors = listOf(AccentOrange, NeonCyan, AccentOrange),
        center = center
    )
}

@Composable
fun BookmarksTabContent(viewModel: LocationViewModel) {
    val context = LocalContext.current
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredBookmarks = remember(bookmarks, searchQuery) {
        if (searchQuery.isBlank()) {
            bookmarks
        } else {
            bookmarks.filter { 
                it.name.contains(searchQuery, ignoreCase = true) || 
                it.address.contains(searchQuery, ignoreCase = true) 
            }
        }
    }

    var editTargetBookmark by remember { mutableStateOf<LocationBookmark?>(null) }
    var editNameInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search & filter header
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(text = "搜索收藏坐标、文件名、地址...", color = TextGray) },
            prefix = { Icon(imageVector = Icons.Default.Search, contentDescription = "搜索", tint = TextGray, modifier = Modifier.padding(end = 8.dp)) },
            suffix = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "清除", tint = TextGray)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AccentOrange,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedContainerColor = PanelDarkBg,
                unfocusedContainerColor = PanelDarkBg
            ),
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .testTag("bookmark_search")
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredBookmarks.isEmpty()) {
            // High fidelity styled Empty State
            EmptyBookmarksState(hasSearch = searchQuery.isNotBlank())
        } else {
            // Lazy List of bookmarked properties
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("bookmarks_list")
            ) {
                items(filteredBookmarks, key = { it.id }) { bookmark ->
                    BookmarkCard(
                        bookmark = bookmark,
                        onNavigate = {
                            val mapUri = Uri.parse("geo:${bookmark.latitude},${bookmark.longitude}?q=${bookmark.latitude},${bookmark.longitude}(${Uri.encode(bookmark.name)})")
                            val mapIntent = Intent(Intent.ACTION_VIEW, mapUri)
                            try {
                                context.startActivity(mapIntent)
                            } catch (e: Exception) {
                                // Fallback browser mapping if map apps are absent
                                val webUri = Uri.parse("https://uri.amap.com/marker?position=${bookmark.longitude},${bookmark.latitude}&name=${Uri.encode(bookmark.name)}")
                                val webIntent = Intent(Intent.ACTION_VIEW, webUri)
                                context.startActivity(webIntent)
                            }
                        },
                        onShare = {
                            shareSavedBookmark(context, bookmark)
                        },
                        onEdit = {
                            editNameInput = bookmark.name
                            editTargetBookmark = bookmark
                        },
                        onDelete = {
                            viewModel.deleteBookmark(bookmark)
                            Toast.makeText(context, "书签已成功删除", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Rename/Edit Dialog
    if (editTargetBookmark != null) {
        AlertDialog(
            onDismissRequest = { editTargetBookmark = null },
            title = { Text(text = "编辑书签标题", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editNameInput,
                    onValueChange = { editNameInput = it },
                    placeholder = { Text(text = "书签名称...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentOrange,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedLabelColor = AccentOrange
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_bookmark_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editTargetBookmark?.let {
                            viewModel.updateBookmarkName(it, editNameInput)
                        }
                        editTargetBookmark = null
                        Toast.makeText(context, "书签标题已修改", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("edit_dialog_confirm")
                ) {
                    Text(text = "确认更新", color = AccentOrange, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editTargetBookmark = null }) {
                    Text(text = "取消", color = Color.Gray)
                }
            },
            containerColor = PanelDarkBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun EmptyBookmarksState(hasSearch: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (hasSearch) Icons.Default.Info else Icons.Default.FavoriteBorder,
            contentDescription = "无星标",
            tint = Color.Gray.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (hasSearch) "无搜索配对" else "尚无收藏书签",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasSearch) "请尝试键入其他更通用的搜索词句。" else "您在首屏获取得的GPS定位点，点击'保存为书签'即可保存在这里，在无信号断网状态下依旧可精确查找。",
            color = TextGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun BookmarkCard(
    bookmark: LocationBookmark,
    onNavigate: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val parsedDate = remember(bookmark.timestamp) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(bookmark.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bookmark_card_${bookmark.id}"),
        colors = CardDefaults.cardColors(containerColor = PanelDarkBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Bookmark header title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookmark.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = parsedDate,
                        color = TextGray,
                        fontSize = 11.sp
                    )
                }
                
                // Top header controls
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "重命名", tint = TextGray, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Meta specs readout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "纬度", color = TextGray, fontSize = 9.sp)
                    Text(
                        text = String.format(Locale.US, "%.5f° %s", Math.abs(bookmark.latitude), if (bookmark.latitude >= 0) "N" else "S"),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column {
                    Text(text = "经度", color = TextGray, fontSize = 9.sp)
                    Text(
                        text = String.format(Locale.US, "%.5f° %s", Math.abs(bookmark.longitude), if (bookmark.longitude >= 0) "E" else "W"),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column {
                    Text(text = "海拔高度", color = TextGray, fontSize = 9.sp)
                    Text(
                        text = "${String.format(Locale.US, "%.1f", bookmark.altitude)} 米",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Address subtitle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.LocationOn, contentDescription = "坐标位置", tint = NeonCyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = bookmark.address.ifEmpty { "离线保存的卫星坐标星标" },
                    color = TextGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            if (bookmark.directionAngle != 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Explore, contentDescription = "方向", tint = AccentOrange, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "保存时指向角度: ${bookmark.directionAngle.toInt()}° (${getChineseBearingDirection(bookmark.directionAngle)})",
                        color = TextGray,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interaction button block
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy button
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("经纬度", "${bookmark.latitude}, ${bookmark.longitude}")
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "经纬度坐标已精准复制！", Toast.LENGTH_SHORT).show()
                    },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "复制", tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "复制坐标", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Share bookmark button
                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "分享", tint = AccentOrange, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "一键分享", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Navigation button
                Button(
                    onClick = onNavigate,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(38.dp)
                        .testTag("action_navigate_${bookmark.id}")
                ) {
                    Icon(imageVector = Icons.Default.Navigation, contentDescription = "寻路导航", tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "系统地图导航", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Share helpers using native action chooser intent
private fun shareLocation(context: Context, location: Location, address: String, bearing: Float) {
    val bearingStr = getChineseBearingDirection(bearing)
    val text = """
        📍 【我的实时和定位分享】
        位置估算: $address
        地球纬度: ${String.format(Locale.US, "%.6f", location.latitude)} (北纬/南纬)
        地球经度: ${String.format(Locale.US, "%.6f", location.longitude)} (东经/西经)
        当前海拔: ${String.format(Locale.US, "%.1f", location.altitude)} 米
        实时朝向: ${bearing.toInt()}° $bearingStr 方向
        在线高德地图查看位置: https://uri.amap.com/marker?position=${location.longitude},${location.latitude}&name=我的坐标
        在线谷歌地图查看位置: https://maps.google.com/?q=${location.latitude},${location.longitude}
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享我的实时定位"))
}

private fun shareSavedBookmark(context: Context, bookmark: LocationBookmark) {
    val bearingStr = getChineseBearingDirection(bookmark.directionAngle)
    val text = """
        📌 【位置书签推荐分享】
        坐标名称: ${bookmark.name}
        位置估算: ${bookmark.address.ifEmpty { "离线存储历史坐标点" }}
        地球纬度: ${String.format(Locale.US, "%.6f", bookmark.latitude)}
        地球经度: ${String.format(Locale.US, "%.6f", bookmark.longitude)}
        估计海拔: ${String.format(Locale.US, "%.1f", bookmark.altitude)} 米
        朝向方位: ${bookmark.directionAngle.toInt()}° $bearingStr
        在线高德地图查阅: https://uri.amap.com/marker?position=${bookmark.longitude},${bookmark.latitude}&name=${Uri.encode(bookmark.name)}
        在线谷歌地图查阅: https://maps.google.com/?q=${bookmark.latitude},${bookmark.longitude}
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享保存的地球坐标书签"))
}

// Converts bearing azimuth to high compatibility simple Chinese cardinals
private fun getChineseBearingDirection(degrees: Float): String {
    val norm = (degrees % 360f + 360f) % 360f
    return when (norm) {
        in 337.5..360.0, in 0.0..22.5 -> "正北 N"
        in 22.5..67.5 -> "东北 NE"
        in 67.5..112.5 -> "正东 E"
        in 112.5..157.5 -> "东南 SE"
        in 157.5..202.5 -> "正南 S"
        in 202.5..247.5 -> "西南 SW"
        in 247.5..292.5 -> "正西 W"
        in 292.5..337.5 -> "西北 NW"
        else -> "未知"
    }
}
