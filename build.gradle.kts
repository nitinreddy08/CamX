// Top-level build file. Plugins are declared here (apply false) so every module uses the same versions.
// Note: AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no separate kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
