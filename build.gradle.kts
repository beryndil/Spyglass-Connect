import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Calendar
import java.util.TimeZone

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.spyglass.connect"

// CalVer: same scheme as the Android app (ZodiacName.MMDD.HHmm-d)
val ZODIAC_NAMES = mapOf(
    2024 to "WoodDragon", 2025 to "WoodSnake",
    2026 to "FireHorse", 2027 to "FireGoat",
    2028 to "EarthMonkey", 2029 to "EarthRooster",
    2030 to "MetalDog", 2031 to "MetalPig",
    2032 to "WaterRat", 2033 to "WaterOx",
    2034 to "WoodTiger", 2035 to "WoodRabbit",
)
val ZODIAC_YEARS = ZODIAC_NAMES.entries.associate { (k, v) -> v to k }

fun computeCalVer(): String {
    val ts = System.getenv("BUILD_TIMESTAMP") // e.g. "FireHorse.0307.0806" from CI
    if (ts != null) return "$ts-d"
    val d = Calendar.getInstance(TimeZone.getTimeZone("America/Chicago"))
    val year = d.get(Calendar.YEAR)
    val zodiac = ZODIAC_NAMES[year] ?: error("No zodiac name for year $year")
    return "%s.%02d%02d.%02d%02d-d".format(
        zodiac, d.get(Calendar.MONTH) + 1,
        d.get(Calendar.DAY_OF_MONTH), d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE),
    )
}
val calVerName = computeCalVer()
version = calVerName

kotlin {
    jvmToolchain(21)
}

// Generate BuildConfig.kt so code can reference the version at runtime
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("com/spyglass/connect")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            |package com.spyglass.connect
            |
            |object BuildConfig {
            |    const val VERSION_NAME = "$calVerName"
            |}
            """.trimMargin()
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateBuildConfig.map { layout.buildDirectory.dir("generated/buildconfig") })
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Ktor WebSocket server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)

    // Ktor HTTP client (Pterodactyl API)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // NBT / Minecraft
    implementation(libs.querz.nbt)

    // QR code generation
    implementation(libs.zxing.core)

    // mDNS service discovery
    implementation(libs.jmdns)

    // Encryption (ECDH + AES-GCM)
    implementation(libs.bouncycastle)

    // Testing
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.spyglass.connect.SpyglassConnectKt"

        jvmArgs(
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=100",
            "-Xmx512m",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Spyglass Connect"
            packageVersion = "1.0.0" // Native packaging requires semver; CalVer is in BuildConfig
            description = "Minecraft companion — stream world data to Spyglass on your phone"
            vendor = "Spyglass Connect"

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Spyglass Connect"
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID = "com.spyglass.connect"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
