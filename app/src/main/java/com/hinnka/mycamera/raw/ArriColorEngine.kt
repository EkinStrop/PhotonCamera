package com.hinnka.mycamera.raw

import android.content.Context

object ArriColorEngine {
    private const val TAG = "ArriColorEngine"
    private const val LOGC4_TO_GAMMA24_REC709_ASSET =
        "arri/ARRI_LogC4-to-Gamma24_Rec709-D65_v1_65.plut"

    @Volatile
    private var cachedLogC4ToGamma24Rec709: ArriLut? = null

    fun loadLogC4ToGamma24Rec709Lut(context: Context): ArriLut? {
        cachedLogC4ToGamma24Rec709?.let { return it }
        return synchronized(this) {
            cachedLogC4ToGamma24Rec709
                ?: RawEngineLutLoader.loadPlut(context, LOGC4_TO_GAMMA24_REC709_ASSET, TAG)
                    ?.also { cachedLogC4ToGamma24Rec709 = it }
        }
    }
}
