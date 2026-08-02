// HoEL-Builder needs the Citizens API for NPC creation, tagging, and interaction events,
// and the Floodgate API to send Bedrock players a native Form instead of the Java chest
// GUI. No other module depends on either, so the repos/dependencies stay local to this
// module. Floodgate's API version is pinned to match what's actually installed on the
// dev server (see CLAUDE.md) rather than floating to whatever's newest.

repositories {
    maven {
        name = "citizens"
        url = uri("https://maven.citizensnpcs.co/repo")
    }
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
    compileOnly("net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
}
