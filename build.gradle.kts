plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "burpmcp"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.fasterxml.jackson", "burpmcp.shaded.jackson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
