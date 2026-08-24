plugins { `java-library` }

dependencies {
    api(project(":fiq-domain"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.httpclient)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
}

