plugins {
    id(hnau.plugins.kotlin.serialization.get().pluginId)
    id(hnau.plugins.hnau.kmp.get().pluginId)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":common"))
                api(hnau.ktor.server.core)
                implementation(hnau.ktor.server.websockets)
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":client"))
                implementation(hnau.ktor.server.cio)
                implementation(hnau.ktor.client.cio)
            }
        }
    }
}
