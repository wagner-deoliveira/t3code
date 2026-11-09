plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "codes.t3"
version = "0.3.0"

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
