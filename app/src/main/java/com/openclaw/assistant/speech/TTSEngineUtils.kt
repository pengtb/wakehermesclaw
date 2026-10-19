package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech

object TTSEngineUtils {
    
    data class EngineInfo(
        val name: String,
        val label: String,
        val icon: Int? = null // Optional icon resource
    )

    fun getAvailableEngines(context: Context): List<EngineInfo> {
        val pm = context.packageManager
        val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
        val services = pm.queryIntentServices(intent, 0)
        
        return services.map { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo
            val applicationInfo = serviceInfo.applicationInfo
            val label = applicationInfo.loadLabel(pm).toString()
            val packageName = serviceInfo.packageName
            
            // Add package name to label for clarity if it's generic
            val displayLabel = if (label == "Text-to-speech engine" || label == "TTS") {
                "$label ($packageName)"
            } else {
                label
            }

            EngineInfo(
                name = packageName, 
                label = displayLabel
            )
        }.distinctBy { it.name } // Ensure no duplicates
    }


    fun getDefaultEngine(context: Context): String? {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth"
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Google 官方的 TTS 引擎包名 */
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * 实际该用哪个引擎：
     * 1) 设置里明确选过、并且真装了 → 用它
     * 2) 否则只要有 Google TTS 就优先 Google（官方的中文最好，也最稳）
     * 3) 都没有 → null，交给系统默认
     */
    fun resolveEngine(context: Context, configured: String): String? {
        val installed = getAvailableEngines(context).map { it.name }.toSet()
        if (configured.isNotBlank() && configured in installed) return configured
        if (GOOGLE_TTS_PACKAGE in installed) return GOOGLE_TTS_PACKAGE
        return null
    }
}
