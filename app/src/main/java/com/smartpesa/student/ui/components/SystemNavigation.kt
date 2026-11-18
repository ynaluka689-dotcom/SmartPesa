package com.smartpesa.student.ui.components

import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Hide navigation keys while leaving Android's status bar and swipe navigation available. */
fun hideNavigationKeys(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.navigationBars())
    }
}

/** Sheets and dialogs have their own windows, so the activity's setting alone is insufficient. */
@Composable
fun HideDialogNavigationKeys() {
    val view = LocalView.current
    DisposableEffect(view) {
        var ancestor: Any? = view
        var dialogWindow: Window? = null
        while (ancestor != null && dialogWindow == null) {
            dialogWindow = (ancestor as? DialogWindowProvider)?.window
            ancestor = (ancestor as? View)?.parent
        }
        dialogWindow?.let(::hideNavigationKeys)
        onDispose { }
    }
}
