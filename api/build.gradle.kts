plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin") version "2.3.7"
}

val ktor_version: String by project
val logback_version: String by project
val postgres_version: String by project
val hikari_version: String by project
val exposed_version: String by project
val dotenv_version: String by project
val kotlinx_coroutines_version: String by project
val kotlinx_serialization_version: String by project

dependencies {
    // Shared модуль
    implementation(project(":shared"))

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")

    // Ktor client
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-client-logging:$ktor_version")

    // Database
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")

    // Корутины и сериализация - ФИКСИРОВАННЫЕ версии
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Environment
    implementation("io.github.cdimascio:dotenv-kotlin:$dotenv_version")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.vireal.api.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("api-all.jar")
    }
}