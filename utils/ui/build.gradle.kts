plugins {
    id(hnau.plugins.ksp.get().pluginId)
    id(hnau.plugins.hnau.kmpAndroidWithCompose.get().pluginId)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":client"))
                api(project(":utils:core"))
                api(hnau.commons.app.projector)
            }
        }
    }
}
