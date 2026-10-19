package com.openclaw.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// Lobster Theme Palette
val OpenClawOrange = Color(0xFFFF5722) // Primary Lobster Color
val OpenClawPopYellow = Color(0xFFFFD600) // Pop Background
val OpenClawDarkGrey = Color(0xFF121212) // Background
val OpenClawSurfaceGrey = Color(0xFF1E1E1E) // Surface
val OpenClawBlue = Color(0xFF283593) // Deprecated: Kept for compat but should be avoided
val OpenClawTextPrimary = Color(0xFFEEEEEE)
val OpenClawTextSecondary = Color(0xFFB0B0B0)
val OpenClawError = Color(0xFFD32F2F)

// WakeHermesClaw onboarding tokens. Keep this flow calmer than the legacy lobster palette:
// it has to read like a setup tool for OpenClaw + Hermes, not a promo screen.
val OnboardingGradientStart = Color(0xFFF8FAFC)
val OnboardingGradientMid = Color(0xFF4F46E5)
val OnboardingGradientEnd = Color(0xFFE0F2FE)
val OnboardingSurface = Color(0xFFFFFFFF)
val OnboardingBorder = Color(0xFFE2E8F0)
val OnboardingTextPrimary = Color(0xFF0F172A)
val OnboardingTextSecondary = Color(0xFF475569)

// 同一套 onboarding 配色的深色版（系统深色时用）
val OnboardingGradientStartDark = Color(0xFF0B1220)
val OnboardingGradientEndDark = Color(0xFF131A2A)
val OnboardingSurfaceDark = Color(0xFF1B1B1F)
val OnboardingBorderDark = Color(0xFF33343A)
val OnboardingTextPrimaryDark = Color(0xFFECEDEF)
val OnboardingTextSecondaryDark = Color(0xFFB0B6BE)
val OnboardingAccentDark = Color(0xFFA5AEFF)
