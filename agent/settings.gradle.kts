rootProject.name = "vsrqg-agent"
dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../backend/gradle/libs.versions.toml")) } }
}
