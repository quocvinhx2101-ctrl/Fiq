plugins {
    id("io.quarkus")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(project(":fiq-domain"))
    implementation(project(":fiq-delta"))
    implementation(project(":fiq-engine-spark"))
    implementation(libs.hadoop.common)
    runtimeOnly(libs.hadoop.aws) {
        // The Hadoop POM selects a 280+ MiB shaded SDK bundle with a legacy Jandex index.
        // Quarkus otherwise re-indexes the entire bundle and exhausts production-build memory.
        exclude(group = "software.amazon.awssdk", module = "bundle")
    }
    runtimeOnly(platform("software.amazon.awssdk:bom:2.29.52"))
    runtimeOnly("software.amazon.awssdk:s3")
    runtimeOnly("software.amazon.awssdk:sts")
    runtimeOnly("software.amazon.awssdk:kms")
    runtimeOnly("software.amazon.awssdk:s3-transfer-manager")
    runtimeOnly("software.amazon.awssdk:apache-client")
    runtimeOnly("software.amazon.awssdk:netty-nio-client")
    implementation(libs.cron.utils)

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
