plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("recovery.repoRoot", rootProject.projectDir.absolutePath)
    // R3's history generator runs only when asked: -Drecovery.writeReplayHistories=true (design §7.5, plan D-lite).
    System.getProperty("recovery.writeReplayHistories")?.let { systemProperty("recovery.writeReplayHistories", it) }
    // The new set's directory name (default: today's date); an existing set is never overwritten.
    System.getProperty("recovery.replaySet")?.let { systemProperty("recovery.replaySet", it) }
}
// The Temporal shell around the pure core (design §4.1): workflow, activity interfaces, the Mock narrator.
dependencies {
    implementation(project(":core"))
    implementation(libs.temporal.sdk)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.snakeyaml)   // StrictYaml walks its events directly
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.temporal.testing)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.slf4j.simple)   // test logging binding; without it Testcontainers/Temporal log to NOP
}
