plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(libs.temporal.sdk)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
application { mainClass.set("dev.picasso.recovery.host.WorkerKt") }

// The Gradle daemon keeps the environment it started with, so forward every RECOVERY_* from the live shell into
// the forked JVM. The working directory is host/, so a relative default policy path would miss the repo's policy/:
// pass the absolute path when the variable is unset.
fun JavaExec.forwardRecoveryEnv() {
    System.getenv().filterKeys { it.startsWith("RECOVERY_") }.forEach { (k, v) -> environment(k, v) }
    if (System.getenv("RECOVERY_EPISODE_POLICY") == null) {
        environment("RECOVERY_EPISODE_POLICY", rootProject.file("policy/active.yaml").absolutePath)
    }
}
tasks.named<JavaExec>("run") { forwardRecoveryEnv() }
tasks.register<JavaExec>("watcher") {
    group = "application"
    mainClass.set("dev.picasso.recovery.host.WatcherKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardRecoveryEnv()
}
tasks.register<JavaExec>("cli") {
    group = "application"
    mainClass.set("dev.picasso.recovery.host.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardRecoveryEnv()
}
