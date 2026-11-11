plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
rootProject.name = "incident-recovery"
include("core")
include("runtime")
include("host")
include("api")
