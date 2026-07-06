dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// S3: the root build config publishes the public API module as Java 21 bytecode (class-file
// major version 65) so Java 21 consumers can use it. The core/plugin module stays on Java 25 and
// shades these Java 21 API classes. Do not use Java 22+ language or API features in this module.

// Match the core module: do not let Javadoc doclint fail the build (the api module has no
// dedicated doclint configuration otherwise, and `build` runs javadocJar -> javadoc).
tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
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
