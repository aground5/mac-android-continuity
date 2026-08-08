package com.peri.hardwareinformation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.peri.hardwareinformation.ui.HardwareApp
import com.peri.hardwareinformation.ui.theme.HardwareInformationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HardwareInformationTheme {
                HardwareApp()
            }
        }
    }
}
