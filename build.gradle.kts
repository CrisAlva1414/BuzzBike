// Root build file — plugins declared here, applied in :app.
//
// NOTE: no `org.jetbrains.kotlin.android` here. AGP 9+ ships built-in
// Kotlin support; applying KGP's android plugin is a hard error
// ("no longer required since AGP 9.0"). The Kotlin version used to build
// sources is the one embedded in AGP, not a catalog entry.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}
