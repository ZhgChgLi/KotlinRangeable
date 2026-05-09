import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    `maven-publish`
}

group = "io.github.zhgchgli"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "rangeable"
            from(components["java"])
            pom {
                name.set("rangeable")
                description.set(
                    "Hashable-element interval set with first-insert ordered active queries.",
                )
                url.set("https://github.com/ZhgChgLi/KotlinRangeable")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("ZhgChgLi")
                        name.set("ZhgChgLi")
                        email.set("zhgchgli@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/ZhgChgLi/KotlinRangeable.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ZhgChgLi/KotlinRangeable.git")
                    url.set("https://github.com/ZhgChgLi/KotlinRangeable")
                }
            }
        }
    }
}
