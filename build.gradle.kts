import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.4.10"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("com.palantir.graal") version "0.7.1-20-g113a84d"
}

group = "wstunnel"
version = "1.0.0"
java.sourceCompatibility = JavaVersion.VERSION_1_8

application {
    mainClass.set("wstunnel.MainKt")
    mainClassName = mainClass.get() // TODO: Remove when shadow plugin will add support for mainClass
}

graal {
    graalVersion("20.2.0")
    javaVersion("11")
    outputName("wstunnel")
    mainClass(application.mainClass.get())
}

repositories {
    mavenCentral()
    jcenter()
    maven {
        url = uri("https://kotlin.bintray.com/kotlinx")
    }
}

val ktorVersion = "1.4.1"
val sshdVersion = "2.5.1"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3")

    implementation("org.apache.sshd:sshd-core:$sshdVersion")
    implementation("org.apache.sshd:sshd-sftp:$sshdVersion")

    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-websockets:$ktorVersion")
    implementation("io.ktor:ktor-network:$ktorVersion")

    testImplementation("io.ktor:ktor-server-tests:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.6.1")
    testImplementation("org.mockito:mockito-core:3.3.3")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
}

tasks {
    withType<KotlinCompile>().all {
        with(kotlinOptions) {
            jvmTarget = "1.8"

            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xjsr305=strict",
                "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-Xuse-experimental=kotlinx.coroutines.ObsoleteCoroutinesApi",
                "-Xuse-experimental=kotlinx.coroutines.FlowPreview",
                "-Xuse-experimental=kotlinx.cli.ExperimentalCli",
                "-Xuse-experimental=kotlin.time.ExperimentalTime",
                "-Xuse-experimental=io.ktor.util.KtorExperimentalAPI"
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

    withType<ShadowJar> {
        minimize()
    }
}
