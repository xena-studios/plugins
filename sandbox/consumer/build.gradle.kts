// Never released. Uses the sandbox's API from another plugin, on a real server in CI,
// and xRewind's, which needs a real server to roll back on.
plugins {
    id("xena.java")
}

dependencies {
    compileOnly(project(":sandbox:api"))
    compileOnly(project(":xrewind:api"))
}
