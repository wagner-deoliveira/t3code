import java.util.Base64

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "codes.t3"
version = "0.4.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val installedPlatform = providers.gradleProperty("platformPath")
        if (installedPlatform.isPresent) {
            local(installedPlatform.get())
        } else {
            webstorm(providers.gradleProperty("platformVersion").get())
        }
        bundledModule("com.intellij.modules.jcef")
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

intellijPlatform {
    pluginConfiguration {
        name = "T3 Code"
        ideaVersion {
            sinceBuild = "263"
        }
    }
}

tasks.processResources {
    val screenshots = mapOf(
        "connectedScreenshot" to layout.projectDirectory.file("screenshots/compact-toolbar.png"),
        "connectScreenshot" to layout.projectDirectory.file("screenshots/connect.png"),
        "lightScreenshot" to layout.projectDirectory.file("screenshots/light-theme.png"),
    )
    inputs.files(screenshots.values)
    filesMatching("**/plugin.xml") {
        expand(screenshots.mapValues { (_, file) ->
            "data:image/png;base64," + Base64.getEncoder().encodeToString(file.asFile.readBytes())
        })
    }
}
