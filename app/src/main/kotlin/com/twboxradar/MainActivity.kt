package com.twboxradar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.twboxradar.ui.RadarApp
import com.twboxradar.ui.RadarViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: RadarViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ads = (application as RadarApplication).ads
        ads.attach(this)
        setContent {
            RadarApp(
                viewModel = viewModel,
                ads = ads,
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
    }
}
