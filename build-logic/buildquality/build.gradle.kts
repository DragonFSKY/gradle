plugins {
    id("gradlebuild.build-logic.kotlin-dsl-gradle-plugin")
}

description = "Provides plugins to configure quality checks (incubating report, CodeNarc, et al)"

dependencies {
    implementation("gradlebuild:basics")

    implementation(projects.cleanup)
    implementation(projects.documentation)
    implementation(projects.integrationTesting)
    implementation(projects.jvm)
    implementation(projects.performanceTesting)
    implementation(projects.profiling)
    implementation(projects.binaryCompatibility)
    implementation(projects.dependencyModules)

    implementation(buildLibs.codenarc) {
        exclude(group = "org.apache.groovy")
        exclude(group = "org.codehaus.groovy")
    }
    implementation(buildLibs.javaParserSymbolSolver) {
        exclude(group = "com.google.guava")
    }
    implementation(buildLibs.kgp)
    compileOnly(buildLibs.kotlinCompilerEmbeddable) {
        because("Required by IncubatingApiReportTask and Gradle10RemovalReportTask")
    }
    implementation(buildLibs.develocityPlugin) {
        because("Arch-test plugin configures the PTS extension")
    }

    testImplementation(buildLibs.commonsLang3)
    testImplementation(buildLibs.kotlinCompilerEmbeddable) {
        because("RemovalReportWorkActionTest parses Kotlin sources via KotlinSourceParser")
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
