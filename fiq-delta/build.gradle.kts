plugins { `java-library` }

dependencies {
    api(project(":fiq-domain"))
    implementation(libs.delta.kernel.api)
    implementation(libs.delta.kernel.defaults)
    implementation(libs.jackson.databind)
    implementation(libs.hadoop.common)
    // Delta Kernel defaults declares Hadoop client 3.4.0. Align its shaded API/runtime with
    // hadoop-common and hadoop-aws so S3A never loads classes from different Hadoop patches.
    implementation(libs.hadoop.client.api)
    runtimeOnly(libs.hadoop.client.runtime)
    runtimeOnly(libs.hadoop.aws) {
        // The server supplies the equivalent modular AWS SDK runtime. Avoid propagating the
        // shaded SDK bundle and its legacy Jandex index into Quarkus augmentation.
        exclude(group = "software.amazon.awssdk", module = "bundle")
    }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
}
