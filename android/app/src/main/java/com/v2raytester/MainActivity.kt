package com.v2raytester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v2raytester.ui.TesterScreen
import com.v2raytester.ui.theme.V2rayTesterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            V2rayTesterTheme {
                val vm: TesterViewModel = viewModel()
                TesterScreen(vm)
            }
        }
    }
}
