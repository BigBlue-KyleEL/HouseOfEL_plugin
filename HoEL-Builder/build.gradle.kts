// HoEL-Builder needs the Citizens API for NPC creation, tagging, and interaction events,
// and the Floodgate API to send Bedrock players a native Form instead of the Java chest
// GUI. No other module depends on either, so the repos/dependencies stay local to this
// module. Floodgate's API version is pinned to match what's actually installed on the
// dev server (see CLAUDE.md) rather than floating to whatever's newest.
//
// Also the only module needing a bundled runtime dependency: the Phase 1-F Toil chassis
// is SQLite-backed, and Paper doesn't ship a JDBC driver on the classpath. `compileOnly`
// (the pattern above for Citizens/Floodgate, which the server provides at runtime) won't
// work here — sqlite-jdbc has to actually be IN this module's jar. Shadow builds that fat
// jar. Deliberately NOT relocating org.sqlite — confirmed by a standalone smoke test that
// relocating it breaks the native JNI bridge: sqlite-jdbc's bundled .dll/.so has
// `org/sqlite/core/NativeDB` hardcoded as a JNI symbol at compile time, which no bytecode
// relocator can rewrite, so a relocated build throws NoClassDefFoundError the moment it
// tries to open a real connection. Shipping the driver under its real package name is the
// standard practice for this specific library for exactly that reason.

plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

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
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
}

tasks.shadowJar {
    // No classifier — this becomes the one jar in build/libs, so the deploy step in
    // CLAUDE.md ("copy the jar into plugins/") still just needs the one file, not a
    // "-all"-suffixed one alongside a useless thin jar.
    archiveClassifier.set("")
}

// The plain `jar` task only produces the thin jar (no sqlite-jdbc inside it) — without
// this, `build`/`assemble` wouldn't actually depend on shadowJar, and a plugins/ folder
// copy of build/libs/HoEL-Builder-0.1.0-SNAPSHOT.jar would silently be missing its JDBC
// driver.
tasks.build {
    dependsOn(tasks.shadowJar)
}
