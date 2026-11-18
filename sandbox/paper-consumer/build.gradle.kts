// Never released. Uses the sandbox's API from another plugin, on a real server in CI.
plugins {
    id("xena.java")
}

dependencies {
    compileOnly(project(":sandbox:api"))
}
