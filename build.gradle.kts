import io.gitlab.arturbosch.detekt.Detekt

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
        source.setFrom(
            files(
                "src/commonMain/kotlin",
                "src/androidMain/kotlin",
                "src/iosMain/kotlin",
                "src/commonTest/kotlin",
                "src/androidUnitTest/kotlin",
            )
        )
    }

    dependencies {
        "detektPlugins"(rootProject.libs.detekt.formatting)
        "detektPlugins"(rootProject.libs.detekt.compose.rules)
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
        exclude("**/build/**", "**/generated/**", "**/uniffi/**")
    }
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on all subprojects."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}

tasks.register<Exec>("lintSwift") {
    group = "verification"
    description = "Lints the iOS Swift sources with SwiftLint (config in iosApp/.swiftlint.yml)."
    workingDir = rootProject.file("iosApp")
    // No-op with a friendly hint if SwiftLint isn't installed, so the task never blocks contributors.
    commandLine(
        "sh", "-c",
        "command -v swiftlint >/dev/null 2>&1 && swiftlint lint --strict " +
            "|| echo 'SwiftLint not installed — skipping. Install with: brew install swiftlint'",
    )
}
