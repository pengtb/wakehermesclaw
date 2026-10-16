package com.openclaw.assistant.speech.diagnostics

import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.openclaw.assistant.R
import java.util.Locale

/**
 * Data structure for voice diagnostic results
 */
data class VoiceDiagnostic(
    val sttStatus: DiagnosticStatus,
    val ttsStatus: DiagnosticStatus,
    val sttEngine: String? = null,
    val ttsEngine: String? = null,
    val missingLanguages: List<String> = emptyList(),
    val suggestions: List<DiagnosticSuggestion> = emptyList()
)

enum class DiagnosticStatus {
    READY,      // Ready
    WARNING,    // Warning (configuration required)
    ERROR       // Error (inoperable)
}

data class DiagnosticSuggestion(
    val message: String,
    val actionLabel: String? = null,
    val intent: Intent? = null,
    val isSystemSetting: Boolean = false
)

/**
 * Class to perform diagnostic of voice features
 */
class VoiceDiagnostics(private val context: Context) {

    fun performFullCheck(tts: TextToSpeech?): VoiceDiagnostic {
        val sttResult = checkSTT()
        val ttsResult = checkTTS(tts)
        
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        suggestions.addAll(sttResult.suggestions)
        suggestions.addAll(ttsResult.suggestions)

        return VoiceDiagnostic(
            sttStatus = sttResult.status,
            ttsStatus = ttsResult.status,
            sttEngine = sttResult.engine,
            ttsEngine = ttsResult.engine,
            missingLanguages = ttsResult.missingLangs,
            suggestions = suggestions
        )
    }

    private data class ComponentCheckResult(
        val status: DiagnosticStatus,
        val engine: String? = null,
        val suggestions: List<DiagnosticSuggestion> = emptyList(),
        val missingLangs: List<String> = emptyList()
    )

    private fun checkSTT(): ComponentCheckResult {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!isAvailable) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                suggestions = listOf(
                    DiagnosticSuggestion(
                        context.getString(R.string.diag_stt_not_found),
                        context.getString(R.string.diag_action_open_store),
                        null
                    )
                )
            )
        }
        return ComponentCheckResult(
            status = DiagnosticStatus.READY,
            engine = context.getString(R.string.diag_engine_default)
        )
    }

    private fun checkTTS(tts: TextToSpeech?): ComponentCheckResult {
        val engine = tts?.defaultEngine
        val currentLocale = Locale.getDefault()
        var status = DiagnosticStatus.READY
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        
        if (tts == null || engine == null) {
            // Check if Google TTS is actually installed via Package Manager
            val isGoogleInstalled = try {
                context.packageManager.getPackageInfo("com.google.android.tts", 0)
                true
            } catch (e: Exception) {
                false
            }

            val msg = if (isGoogleInstalled) {
                context.getString(R.string.diag_tts_hidden)
            } else {
                context.getString(R.string.diag_tts_null)
            }

            suggestions.add(
                DiagnosticSuggestion(
                    msg,
                    context.getString(R.string.diag_action_fix_in_settings),
                    Intent("com.android.settings.TTS_SETTINGS"),
                    true
                )
            )
            
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                engine = "null/hidden",
                suggestions = suggestions
            )
        }

        val langResult = tts.isLanguageAvailable(currentLocale)
        if (langResult < TextToSpeech.LANG_AVAILABLE) {
            status = DiagnosticStatus.WARNING
            suggestions.add(
                DiagnosticSuggestion(
                    context.getString(R.string.diag_tts_lang_missing, currentLocale.displayName, engine),
                    context.getString(R.string.diag_action_manage_data),
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        if (engine != "com.google.android.tts") {
            suggestions.add(
                DiagnosticSuggestion(
                    context.getString(R.string.diag_tts_switch_google, engine),
                    context.getString(R.string.diag_action_select_google),
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        return ComponentCheckResult(
            status = status,
            engine = engine,
            suggestions = suggestions
        )
    }
}
