pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "HouseOfEL"

include("HEL-Core", "HEL-Builder", "HEL-Encounters", "HEL-LLM")
include("common", "fabric")
