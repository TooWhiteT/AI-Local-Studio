plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.serialization) apply false
}
subprojects {
    if (childProjects.isEmpty()) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "java-library")
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
        dependencies { add("testImplementation", kotlin("test-junit")) }
        tasks.withType<Test>().configureEach { workingDir = rootDir }
        dependencyLocking { lockAllConfigurations() }
    }
}
