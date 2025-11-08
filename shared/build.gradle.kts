plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
}

group = "com.vireal"
version = "1.0.0"

val kotlinx_serialization_version: String by project


repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")
}
