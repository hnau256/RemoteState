plugins {
    id(hnau.plugins.hnau.jvm.get().pluginId)
}

dependencies {
    api(project(":server"))
    api(project(":test:common"))
    implementation(hnau.ktor.server.cio)
}
