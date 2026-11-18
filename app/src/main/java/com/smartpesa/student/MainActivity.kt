package com.smartpesa.student

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import com.smartpesa.student.services.BackgroundJobs
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.smartpesa.student.ui.SmartPesaApp
import com.smartpesa.student.ui.theme.SmartPesaTheme
import com.smartpesa.student.ui.components.hideNavigationKeys

/** Android owns the splash lifetime: no countdown or second splash activity. */
class MainActivity : ComponentActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from the keyboard, permission prompt or export picker restores app navigation.
        if (hasFocus) hideNavigationKeys(window)
    }
    private val destination = mutableStateOf<String?>(null)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); destination.value = intent.getStringExtra("destination") }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep system icons readable even when the device itself uses a light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        hideNavigationKeys(window)
        destination.value = intent.getStringExtra("destination")
        BackgroundJobs.alerts(this)
        if (getSharedPreferences("settings", 0).getBoolean("messages", false)) BackgroundJobs.messages(this)
        setContent { SmartPesaTheme { SmartPesaApp(requestedDestination = destination.value, onDestinationHandled = { destination.value = null; intent.removeExtra("destination") }) } }
    }
}
