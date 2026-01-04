package com.mxlite.app.ui.player

import android.app.Activity
import android.view.WindowInsets
import android.view.WindowInsetsController

fun Activity.enterImmersiveMode() {
    window.insetsController?.apply {
        hide(
            WindowInsets.Type.statusBars() or
            WindowInsets.Type.navigationBars()
        )
        systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun Activity.exitImmersiveMode() {
    window.insetsController?.show(
        WindowInsets.Type.statusBars() or
        WindowInsets.Type.navigationBars()
    )
}
