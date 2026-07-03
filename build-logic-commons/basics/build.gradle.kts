plugins {
    `kotlin-dsl`
}

description = "Provides plugins for configuring miscellaneous things (repositories, reproducibility, minify)"

group = "gradlebuild"

dependencies {
    api("gradlebuild:build-environment")
    api(platform(projects.buildPlatform))

    implementation(buildLibs.guava) {
        because("Used by class analysis")
    }
    implementation(libs.asm) {
        because("Used by class analysis")
    }
    implementation(libs.asmCommons) {
        because("Used by class analysis")
    }

    compileOnly(buildLibs.kotlinCompilerEmbeddable) {
        because("Required by KotlinSourceParser")
    }
    implementation(buildLibs.kgp) {
        because("For manually defined KotlinSourceSet accessor - sourceSets.main.get().kotlin")
    }

    testImplementation(buildLibs.kotlinCompilerEmbeddable) {
        because("KotlinSourceParserTest parses Kotlin sources with the embeddable compiler")
    }
}

configurations.testRuntimeClasspath {
    // The kotlin-gradle-plugin jar bundles a partial, gradle-patched subset of the shaded
    // `org.jetbrains.kotlin.com.intellij.*` classes. Its MockProject implements
    // ComponentManagerEx, an interface that jar does NOT bundle, so when it shadows the complete
    // classes from kotlin-compiler-embeddable, constructing KotlinCoreProjectEnvironment fails
    // with NoClassDefFoundError. The test only needs the compiler-embeddable, so keep kgp off the
    // test runtime classpath to let the complete embeddable classes win.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-gradle-plugin")
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(buildLibs.versions.junit)
        }
    }
}
