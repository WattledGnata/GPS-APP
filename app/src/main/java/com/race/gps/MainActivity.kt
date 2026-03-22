package com.race.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.race.gps.ui.TestFlowNavigation
import com.race.gps.ui.theme.NeonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeonTheme {
                TestFlowNavigation()
            }
        }
    }
}
