plugins {
    scala
    application
    id("com.gradleup.shadow") version "9.0.2"
}

dependencies {
    implementation(project(":fiq-domain"))
    implementation(libs.scala.library)
    compileOnly(libs.spark.sql)
    compileOnly(libs.delta.spark)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
}

application { mainClass.set("io.fiq.spark.MaintenanceJob") }

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature")
}
