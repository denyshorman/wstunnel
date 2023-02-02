import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.8.0"
    kotlin("plugin.serialization") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "wstunnel"
version = "1.0.0"
java.sourceCompatibility = JavaVersion.VERSION_1_8

application {
    mainClass.set("wstunnel.MainKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.2.3"
val sshdVersion = "2.9.2"
val kotestVersion = "5.5.4"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.4.5")
    implementation("io.github.microutils:kotlin-logging:3.0.4")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")

    implementation("org.apache.sshd:sshd-core:$sshdVersion")
    implementation("org.apache.sshd:sshd-sftp:$sshdVersion")

    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-network:$ktorVersion")

    testImplementation("io.ktor:ktor-server-tests:$ktorVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-json:$kotestVersion")
    testImplementation("io.mockk:mockk:1.13.4")
}

tasks {
    withType<KotlinCompile>().all {
        with(kotlinOptions) {
            jvmTarget = "1.8"

            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xjsr305=strict",
            )
        }
    }

    withType<Test> {
        useJUnitPlatform()
    }

    withType<Jar> {
        archiveFileName.set("wstunnel.jar")

        manifest {
            attributes(
                mapOf(
                    "Main-Class" to application.mainClass.get()
                )
            )
        }
    }
}
