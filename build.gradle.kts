plugins {
    alias(libs.plugins.android.application) apply false
    // Also lifts AGP's built-in Kotlin to this version, which current Compose needs
    alias(libs.plugins.kotlin.compose) apply false
}
