plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("com.github.johnrengelman.shadow")
  application
}

val ktor_version: String by project
val tgbotapi_version: String by project
val dotenv_version: String by project
val logback_version: String by project
val kotlinx_coroutines_version: String by project
val kotlinx_serialization_version: String by project

dependencies {
  // Shared модуль
  implementation(project(":shared"))

  // TelegramBotAPI - БЕЗ extensions.utils (он включен в основной пакет)
  implementation("dev.inmo:tgbotapi:$tgbotapi_version")
  implementation("dev.inmo:tgbotapi.behaviour_builder:$tgbotapi_version")

  // Ktor client
  implementation("io.ktor:ktor-client-core:$ktor_version")
  implementation("io.ktor:ktor-client-cio:$ktor_version")
  implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
  implementation("io.ktor:ktor-client-logging:$ktor_version")
  implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

  // Корутины и сериализация - ФИКСИРОВАННЫЕ версии
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${kotlinx_serialization_version}")

  // Logging
  implementation("ch.qos.logback:logback-classic:$logback_version")

  // Environment
  implementation("io.github.cdimascio:dotenv-kotlin:$dotenv_version")
  implementation("com.typesafe:config:1.4.3")
}

kotlin {
  jvmToolchain(17)
}

application {
  mainClass.set("com.vireal.bot.MainKt")
}

tasks {
  shadowJar {
    archiveBaseName.set("telegram-bot")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
      attributes["Main-Class"] = "com.vireal.bot.MainKt"
    }
  }
}
