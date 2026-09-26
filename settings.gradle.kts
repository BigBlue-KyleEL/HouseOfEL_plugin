pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "HouseOfEL"

include("HoEL-Core", "HoEL-Builder", "HoEL-Encounters", "HoEL-LLM")
include("common", "fabric")
