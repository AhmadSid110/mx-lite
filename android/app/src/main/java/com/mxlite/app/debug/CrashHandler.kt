package com.mxlite.app.debug

import android.content.Context
import android.widget.Toast
import kotlin.system.exitProcess

object CrashHandler {
    fun install(context: Context) {
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            Toast.makeText(
                context,
                "APP CRASH:\n${t.stackTraceToString().take(400)}",
                Toast.LENGTH_LONG
            ).show()

            Thread.sleep(4000)
            exitProcess(1)
        }
    }
}
