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

// No runtime dependencies: JSON input lives in :dq-engine-json.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.test {
    useJUnitPlatform()
}
