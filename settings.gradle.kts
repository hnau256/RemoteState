rootProject.name = "RemoteState"

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.hnau.plugin.settings") version "1.30.17"
}

hnau {
    publish {
        version = "1.0.0"
        gitUrl = "https://github.com/hnau256/remote_state"
    }
}
