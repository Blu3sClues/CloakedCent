// Top-level build file. Plugins are declared here once and applied per module.
// Kotlin is compiled by AGP's built-in Kotlin support, so there is no
// org.jetbrains.kotlin.android plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
