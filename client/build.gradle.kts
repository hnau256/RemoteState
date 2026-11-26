plugins {
    id(hnau.plugins.ksp.get().pluginId)
    id(hnau.plugins.hnau.kmp.get().pluginId)
}

kotlin {
    sourceSets {
        all {
            languageSettings.optIn("org.hnau.remotestate.common.RemoteStateInternalApi")
        }

        commonMain {
            dependencies {
                api(project(":common"))
                api(hnau.ktor.client.core)
            }
        }
    }
}
