
package com.mxlite.app.codec

import android.content.Context
import android.content.pm.PackageManager

object CodecPackManager {

    private const val CODEC_PACK_PACKAGE =
        "com.mxlite.codecpack"

    fun isCodecPackInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(
                CODEC_PACK_PACKAGE, 0
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
