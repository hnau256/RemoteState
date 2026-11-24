plugins {
    id(hnau.plugins.hnau.kmp.get().pluginId)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":common"))
                api(hnau.ktor.client.core)
            }
        }
    }
}
