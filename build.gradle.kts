plugins {
    java
    id("com.diffplug.spotless") version "7.2.1" apply false
    id("io.quarkus") version "3.33.3.1" apply false
}

allprojects {
    group = "io.fiq"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:6.0.0")
    }

    dependencyLocking { lockAllConfigurations() }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.28.0").aosp()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

tasks.register("ci") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
