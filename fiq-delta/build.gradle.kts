plugins { `java-library` }

dependencies {
    api(project(":fiq-domain"))
    implementation(libs.delta.kernel.api)
    implementation(libs.delta.kernel.defaults)
    implementation(libs.jackson.databind)
    implementation(libs.hadoop.common)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
}
