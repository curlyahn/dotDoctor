package com.dot.doctor

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.doctor.ui.AboutScreen
import com.dot.doctor.ui.LabsScreen
import com.dot.doctor.ui.LogScreen
import com.dot.doctor.ui.MainViewModel
import com.dot.doctor.ui.ReportScreen
import com.dot.doctor.ui.SettingsScreen
import com.dot.doctor.ui.theme.Dot
import com.dot.doctor.ui.theme.DotDoctorTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0xE60B0B0C.toInt()),
        )
        super.onCreate(savedInstanceState)
        // 최근 앱 목록의 미리 보기 화면에 건강 기록이 보이지 않게 (스크린샷은 그대로 가능)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        // 헬스 커넥트의 '권한 사용 안내'로 열렸으면 설정 탭(개인정보 설명)으로
        val fromHealth = intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE"

        setContent {
            DotDoctorTheme {
                var tab by rememberSaveable { mutableIntStateOf(if (fromHealth) 3 else 0) }
                var about by rememberSaveable { mutableStateOf(false) }
                val toast by vm.toast.collectAsStateWithLifecycle()
                LaunchedEffect(toast) {
                    toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show(); vm.toastShown() }
                }
                if (about) {
                    AboutScreen(onBack = { about = false })
                    return@DotDoctorTheme
                }
                Scaffold(
                    containerColor = Dot.Ink,
                    bottomBar = {
                        NavigationBar(containerColor = Dot.Surface) {
                            listOf(
                                Triple("리포트", Icons.Outlined.MedicalServices, 0),
                                Triple("검사", Icons.Outlined.Biotech, 1),
                                Triple("기록", Icons.Outlined.EditNote, 2),
                                Triple("설정", Icons.Outlined.Settings, 3),
                            ).forEach { (label, icon, i) ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Dot.OnLime, indicatorColor = Dot.Lime,
                                        selectedTextColor = Dot.Lime, unselectedIconColor = Dot.Muted, unselectedTextColor = Dot.Muted,
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> ReportScreen(vm, padding)
                        1 -> LabsScreen(vm, padding)
                        2 -> LogScreen(vm, padding)
                        else -> SettingsScreen(vm, padding, onOpenAbout = { about = true })
                    }
                }
            }
        }
    }
}
