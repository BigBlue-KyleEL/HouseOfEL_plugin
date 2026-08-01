// HoEL-Builder needs the Citizens API for NPC creation, tagging, and interaction events.
// No other module depends on Citizens, so the repo/dependency stay local to this module.

repositories {
    maven {
        name = "citizens"
        url = uri("https://maven.citizensnpcs.co/repo")
    }
}

dependencies {
    compileOnly("net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
}
