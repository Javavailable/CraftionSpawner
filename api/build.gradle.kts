dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// S3: the public API module is published as Java 21 bytecode (class-file major version 65) so that
// Java 21 consumers (e.g. CraftionFarmer, which builds on Temurin Java 21) can use it. The core/plugin
// module stays on Java 25 and shades these Java 21 API classes. This override is applied after the
// root allprojects configuration, so it wins for the api module only. Do not use Java 22+ language or
// API features in this module.
val apiJavaVersion = 21
java {
    sourceCompatibility = JavaVersion.toVersion(apiJavaVersion)
    targetCompatibility = JavaVersion.toVersion(apiJavaVersion)
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(apiJavaVersion)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "io.github.javavailable"
            artifactId = "craftionspawner-api"
            from(components["java"])

            pom {
                name.set("CraftionSpawner API")
                description.set("API for CraftionSpawner plugin")
                url.set("https://github.com/Javavailable/CraftionSpawner")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("nighter")
                        name.set("Nighter")
                        email.set("notnighter@gmail.com")
                    }
                }
            }
        }
    }
}
