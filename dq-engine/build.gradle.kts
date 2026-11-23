plugins {
    `java-library`
    `java-test-fixtures`
    // Compile-time only: generates accessors/equals/builders; nothing from Lombok ships at runtime.
    alias(libs.plugins.freefair.lombok)
}

lombok {
    version = libs.versions.lombok
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // Internal only: Jackson types never appear in the exported API package.
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.test {
    useJUnitPlatform { excludeTags("scale") }
}

// F5 proof: one million records through the real streaming input path under a 64 MB heap cap.
val scaleTest = tasks.register<Test>("scaleTest") {
    description = "Runs the bounded-memory scale test with a capped heap."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("scale") }
    maxHeapSize = "64m"
    testLogging { showStandardStreams = true }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(scaleTest) }
