import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run the protocol core as a desktop AirPlay receiver"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.airplaytv.core.DemoReceiverKt")
    workingDir = projectDir
}

tasks.register<JavaExec>("verify") {
    group = "verification"
    description = "Run pure-JVM protocol self-tests"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.airplaytv.core.VerifyKt")
    workingDir = projectDir
}
