plugins {
    java
    application
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

dependencies {
    implementation(project(":api"))
}

// Every javac warning fails the build: the API has to read cleanly from Java.
tasks.compileJava {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

application {
    mainClass = "co.xenastudios.m0.consumer.JavaConsumer"
}
