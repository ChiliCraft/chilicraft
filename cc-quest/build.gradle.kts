plugins {
    id("java-library")
}

dependencies {
    compileOnly(project(":cc-core"))
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.slf4j:slf4j-api:2.0.13")
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
