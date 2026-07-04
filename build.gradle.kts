plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
}

// Apply the Kover plugin to every subproject so its tests are instrumented.
subprojects {
    plugins.apply("org.jetbrains.kotlinx.kover")
}

// Architecture smoke test: feature modules may depend on `core:*` and on
// `feature:catalog` (the shared design-system feature), but never on other
// feature siblings. Run with `./gradlew checkArchitecture`.
tasks.register("checkArchitecture") {
    group = "verification"
    description = "Fails if any feature/* module depends on a sibling feature/* module other than feature:catalog."
    val featureBuildFiles = fileTree(rootDir) {
        include("feature/*/build.gradle.kts")
    }.files.toList()
    inputs.files(featureBuildFiles)
    doLast {
        val violations = mutableListOf<String>()
        val pattern = Regex("""project\("(:feature:[a-z]+)"\)""")
        for (file in featureBuildFiles) {
            val owningModule = ":feature:${file.parentFile.name}"
            val text = file.readText()
            for (match in pattern.findAll(text)) {
                val dep = match.groupValues[1]
                if (dep == owningModule) continue
                if (dep == ":feature:catalog") continue
                violations += "$owningModule depends on $dep"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Architecture violations:\n  " + violations.joinToString(separator = "\n  "),
            )
        }
        logger.lifecycle("Architecture check passed: no forbidden feature -> feature dependencies.")
    }
}

// Apply Spotless to every subproject with a one-liner config — ktlint for
// Kotlin sources, plus trailing-whitespace + EOL normalization.
subprojects {
    plugins.apply("com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.3.0")
                .editorConfigOverride(
                    mapOf(
                        "ktlint_standard_filename" to "disabled",
                        "ktlint_standard_no-wildcard-imports" to "disabled",
                        "ktlint_standard_function-naming" to "disabled",
                        "ktlint_standard_property-naming" to "disabled",
                        "ktlint_standard_class-naming" to "disabled",
                        // The codebase uses Compose @Composable functions named PascalCase
                        // and intentionally @-Preview names with `_` etc. — leave those alone.
                        "ktlint_standard_function-signature" to "disabled",
                        "ktlint_standard_no-empty-first-line-in-class-body" to "disabled",
                    ),
                )
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.3.0")
        }
        format("misc") {
            // Scoped so the file walk never descends into build/ — a `**` glob
            // walks generated dirs before excludes apply, which races KSP
            // output under parallel execution ("Could not read path …/kspCaches").
            target("*.md", "src/**/*.md", ".gitignore")
            targetExclude("**/build/**", "**/.gradle/**")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

// Aggregate Kover reports across every module so `./gradlew koverHtmlReport`
// produces one merged HTML/XML at root build/reports/kover/.
dependencies {
    kover(project(":app"))
    kover(project(":auto"))
    kover(project(":core:model"))
    kover(project(":core:network"))
    kover(project(":core:data"))
    kover(project(":core:playback"))
    kover(project(":core:ui"))
    kover(project(":feature:auth"))
    kover(project(":feature:catalog"))
    kover(project(":feature:details"))
    kover(project(":feature:library"))
    kover(project(":feature:player"))
    kover(project(":feature:search"))
}

kover {
    reports {
        // Don't measure generated code or DI plumbing — they'd skew the numbers.
        filters {
            excludes {
                classes(
                    "*Hilt_*",
                    "*_HiltModules*",
                    "*_Factory",
                    "*_MembersInjector",
                    "dagger.hilt.*",
                    "hilt_aggregated_deps.*",
                    "*BuildConfig",
                    "*ComposableSingletons*",
                    // Compose-generated lambdas and previews
                    "*\$\$Lambda$*",
                )
                packages(
                    "androidx.*",
                    "dagger.hilt.internal.*",
                )
            }
        }

        // Fail `koverVerify` if aggregate coverage regresses. Set to slightly below
        // the current value (62%) so a small drop is tolerated; large drops fail.
        verify {
            rule("Aggregate line coverage stays at 60%+") {
                bound {
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("Aggregate instruction coverage stays at 55%+") {
                bound {
                    minValue = 55
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.INSTRUCTION
                    aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}
