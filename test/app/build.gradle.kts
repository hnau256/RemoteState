plugins {
    id(hnau.plugins.ksp.get().pluginId)
    id(hnau.plugins.kotlin.serialization.get().pluginId)
    id(hnau.plugins.hnau.kmpAndroidWithCompose.get().pluginId)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":utils:ui"))
                implementation(project(":test:common"))
                implementation(hnau.commons.app.model)
            }
        }
        desktopMain {
            dependencies {
                implementation(project(":test:server"))
                implementation(hnau.ktor.client.cio)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.hnau.remotestate.test.app.DesktopAppKt"
    }
}
