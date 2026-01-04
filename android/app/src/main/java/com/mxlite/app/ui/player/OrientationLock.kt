package com.mxlite.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo

fun Activity.lockLandscape() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

fun Activity.unlockOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
