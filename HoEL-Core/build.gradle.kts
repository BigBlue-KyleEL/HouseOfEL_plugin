repositories {
    maven {
        name = "opencollab-snapshots"
        url = uri("https://repo.opencollab.dev/maven-snapshots/")
    }
    maven {
        name = "opencollab-releases"
        url = uri("https://repo.opencollab.dev/maven-releases/")
    }
}

dependencies {
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    implementation(project(":common"))
}
