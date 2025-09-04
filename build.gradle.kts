plugins {
    kotlin("jvm") version "1.9.20" apply false
    kotlin("plugin.serialization") version "1.9.20" apply false
    id("io.ktor.plugin") version "2.3.7" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

subprojects {
    group = "com.vireal"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}
