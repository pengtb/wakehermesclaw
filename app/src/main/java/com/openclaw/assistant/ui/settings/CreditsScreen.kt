package com.openclaw.assistant.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.TTSProviderType
import com.openclaw.assistant.speech.voicevox.VoiceVoxCharacters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit
) {
    val voiceVoxEnabled = BuildConfig.VOICEVOX_ENABLED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credits_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.credits_back_description))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // VOICEVOX Credits (if used)
            if (voiceVoxEnabled && settings.ttsType == TTSProviderType.VOICEVOX) {
                VoiceVoxCreditsSection(settings)
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Speech recognition backend design reference
            val creditsContext = LocalContext.current
            Text(
                stringResource(R.string.credits_asr_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.credits_asr_description),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Skythinker616/gpt-assistant-android"))
                        creditsContext.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        // No browser available; ignore
                    }
                }
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.credits_asr_repo_link))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Open Source Licenses
            Text(
                stringResource(R.string.credits_oss_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(R.string.credits_oss_description),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            val licenses = listOf(
                "Android Jetpack" to "Apache License 2.0",
                "Compose" to "Apache License 2.0",
                "Kotlin" to "Apache License 2.0",
                "OkHttp" to "Apache License 2.0",
                "Gson" to "Apache License 2.0",
                "Vosk" to "Apache License 2.0",
                "HMS ML Kit" to "Huawei SDK License",
                "Bouncy Castle" to "Bouncy Castle License"
            )

            licenses.forEach { (name, license) ->
                Text(
                    "• $name ($license)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (voiceVoxEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                val voiceVoxLicenses = listOf(
                    "VOICEVOX Core" to "MIT License",
                    "onnxruntime" to "MIT License",
                    "Open JTalk" to "Modified BSD License"
                )
                voiceVoxLicenses.forEach { (name, license) ->
                    Text(
                        "• $name ($license)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceVoxCreditsSection(settings: SettingsRepository) {
    val context = LocalContext.current

    Text(
        stringResource(R.string.credits_voicevox_lib_title),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        stringResource(R.string.credits_voicevox_lib_description),
        style = MaterialTheme.typography.bodyMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Currently selected character
    val styleId = settings.voiceVoxStyleId
    val character = VoiceVoxCharacters.getCharacterByStyleId(styleId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.credits_voicevox_current_voice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (character != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    character.creditNotation,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    character.copyright,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Link to terms
                TextButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(character.termsUrl))
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            // No browser available; ignore
                        }
                    }
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.credits_voicevox_open_terms))
                }
            } else {
                // Fallback for unrecognised style IDs - still show something so copyright is visible
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.credits_voicevox_unknown_character, styleId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // General VOICEVOX credit
    Text(
        stringResource(R.string.credits_voicevox_about_title),
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        stringResource(R.string.credits_voicevox_about_description),
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(modifier = Modifier.height(8.dp))

    TextButton(
        onClick = {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://voicevox.hiroshiba.jp/"))
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // No browser available; ignore
            }
        }
    ) {
        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.credits_voicevox_official_site))
    }

    TextButton(
        onClick = {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://voicevox.hiroshiba.jp/term/"))
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // No browser available; ignore
            }
        }
    ) {
        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.credits_voicevox_terms_link))
    }
}
