plugins {
    kotlin("jvm") version "1.9.22"
}

group = "top.kagg886.cctr"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}
