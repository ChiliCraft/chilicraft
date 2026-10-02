// cc-season：季节日历与生态附属
plugins {
    id("java-library")
}

dependencies {
    compileOnly(project(":cc-core"))
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.slf4j:slf4j-api:2.0.13")
    // PlaceholderAPI：季节日历只读变量；运行时缺失时由 softdepend 安全降级
    compileOnly(files("../test-server/plugins/[占位符]PlaceholderAPI-2.12.3.jar"))
    compileOnly(files("../test-server/plugins/[协议库]ProtocolLib.jar"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
