package com.blazepush

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.blazepush.feature.test.ui.TestFlowNavigation
import com.blazepush.feature.test.ui.theme.NeonTheme

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
