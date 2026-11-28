plugins {
    id("neoskript.java-conventions")
}

dependencies {
    api(project(":neoskript-frontend"))
    api(project(":neoskript-runtime"))
    implementation(libs.asm)
    testImplementation(testFixtures(project(":neoskript-frontend")))
    // CheckClassAdapter, for the Java 8 target's classes (#202).
    testImplementation(libs.asm.util)
}
