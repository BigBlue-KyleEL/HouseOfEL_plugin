// Root build config — shared across all four House of EL modules.
// Each module's own build.gradle.kts stays minimal; everything common lives here.

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

subprojects {
    apply(plugin = "java")

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:26.2.build.+")
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
