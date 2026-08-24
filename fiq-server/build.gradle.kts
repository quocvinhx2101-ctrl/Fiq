plugins {
    id("io.quarkus")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(project(":fiq-domain"))
    implementation(project(":fiq-delta"))
    implementation(project(":fiq-engine-spark"))

    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.quarkus:quarkus-oidc")
    implementation("io.quarkus:quarkus-security")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-hibernate-validator")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
}

