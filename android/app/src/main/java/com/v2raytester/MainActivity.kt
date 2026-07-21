package com.v2raytester

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v2raytester.ui.TesterScreen
import com.v2raytester.ui.theme.V2rayTesterTheme

class MainActivity : ComponentActivity() {

    // Progress notification needs runtime permission on Android 13+. Denial is harmless:
    // the foreground service (and therefore the background run) still works, the user
    // just won't see the progress notification.
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            V2rayTesterTheme {
                val vm: TesterViewModel = viewModel()
                TesterScreen(vm)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
