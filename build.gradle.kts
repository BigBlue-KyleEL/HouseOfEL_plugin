// Root build config — shared across all House of EL modules.
// Each module's own build.gradle.kts stays minimal; everything common lives here.
//
// Paper API is scoped to the HEL-* plugin modules only — the common module (pure Java)
// and the fabric module (uses Loom/Fabric API) must not inherit it.
// The fabric module is excluded from the root java/toolchain config entirely — Loom
// manages its own java plugin application and toolchain.

allprojects {
    group = "com.houseofel"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
    }
}

// Java plugin, toolchain, and encoding for all modules EXCEPT fabric (Loom handles its own)
configure(subprojects.filter { it.name != "fabric" }) {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

// Paper API only for plugin modules
configure(subprojects.filter { it.name.startsWith("HEL-") }) {
    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:26.1.2.build.+")
    }
}
