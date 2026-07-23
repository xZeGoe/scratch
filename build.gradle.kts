plugins {
    id("java")
}

group = "net.minestom"
version = "dev"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("net.minestom:minestom:2026.07.22-26.2")
    implementation("org.jctools:jctools-core-jdk11:4.0.6")
    implementation("it.unimi.dsi:fastutil:8.5.18")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
