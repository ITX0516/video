// Root build file. Plugins are declared in gradle/libs.versions.toml and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
