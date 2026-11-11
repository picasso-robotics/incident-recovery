plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    application
}
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("recovery.repoRoot", rootProject.projectDir.absolutePath)
}
dependencies {
    implementation(project(":runtime"))
    implementation(libs.temporal.sdk)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.kotlin)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.temporal.testing)
}
application { mainClass.set("dev.picasso.recovery.api.RecoveryApiApplicationKt") }
tasks.named<JavaExec>("run") {
    System.getenv().filterKeys { it.startsWith("RECOVERY_") }.forEach { (k, v) -> environment(k, v) }
}
