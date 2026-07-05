dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
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
