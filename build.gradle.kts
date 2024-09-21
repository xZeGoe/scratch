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
    implementation("net.minestom:minestom-snapshots:dev")
    implementation("org.jctools:jctools-core:4.0.5")
}