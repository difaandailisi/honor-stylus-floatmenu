package com.batteryanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.batteryanalyzer.crash.CrashExitMonitor
import com.batteryanalyzer.ui.screens.*
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel

// 全局主题颜色上下文
val LocalAppColors = staticCompositionLocalOf { lightThemeColors() }

class MainActivity : ComponentActivity() {

    /** 防止重复请求权限（Activity 重建场景） */
    private var permissionsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isDark = isSystemInDarkTheme()
            val appColors = if (isDark) darkThemeColors() else lightThemeColors()

            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = PrimaryBlue,
                    onPrimary = Color.White,
                    secondary = PrimaryBlueLight,
                    background = BackgroundDark,
                    surface = SurfaceDark
                )
            } else {
                lightColorScheme(
                    primary = PrimaryBlue,
                    onPrimary = Color.White,
                    secondary = PrimaryBlueLight,
                    background = BackgroundLight,
                    surface = SurfaceLight
                )
            }

            CompositionLocalProvider(LocalAppColors provides appColors) {
                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = appColors.background
                    ) {
                        MainApp()
                    }
                }
            }
        }
    }

    /**
     * 在 onResume 中请求权限，而非 onCreate。
     * 【修复】Android 官方要求 ActivityResultLauncher.launch() 必须在 onStart 之后调用。
     * 若在 onCreate 中调用，当 Activity 因系统弹窗（蓝牙配对/权限对话框）被重建时，
     * 会导致 IllegalStateException 闪退。此处移到 onResume 并加一次性标志避免重复请求。
     */
    override fun onResume() {
        super.onResume()
        if (!permissionsRequested) {
            permissionsRequested = true
            requestPermissions()
        }
    }

    /**
     * 用户按 Home 键或切换到其他应用时触发
     * 委托给 CrashExitMonitor 以记录退出事件
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        CrashExitMonitor.onUserLeaveHint()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 使用 BLUETOOTH_SCAN 代替位置权限
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else {
            // Android 11 及以下需要位置权限
            permissions.addAll(listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            android.widget.Toast.makeText(
                this,
                "部分权限被拒绝: ${denied.joinToString()}，可能影响部分功能",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    // v8: App 启动时触发一次手写笔电量记录滚动压缩（低频，避免 battery_records 无限膨胀）
    LaunchedEffect(Unit) { viewModel.runCompaction() }

    val bottomNavItems = listOf(
        BottomNavItem("dashboard", "仪表盘", Icons.Default.Dashboard),
        BottomNavItem("power", "短时功耗", Icons.Default.Bolt),
        BottomNavItem("analysis", "长时分析", Icons.Default.Analytics),
        BottomNavItem("keymapping", "键值映射", Icons.Default.Keyboard),
        BottomNavItem("stylus_sim", "华为手写笔模拟", Icons.Default.Brush),
        BottomNavItem("settings", "设置", Icons.Default.Settings)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            val showBottomBar = currentRoute in bottomNavItems.map { it.route }
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo("dashboard") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable("scan") {
                DeviceScanScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("power") {
                PowerAnalysisScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("analysis") {
                AnalysisScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("keymapping") {
                KeyMappingScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("stylus_sim") {
                StylusSimScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
