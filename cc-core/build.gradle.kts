// cc-core：核心插件（事件总线 / 档案 / 数据库 / 灵魂货币 / 参数服务）
plugins {
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // 服务端 API：运行时由服务端提供，不打进 jar
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 日志门面：服务端自带，不打进 jar
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    // 随 jar 交付的运行时依赖
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // 外部软依赖 API：运行时由服务端插件提供，不打进 jar、不进 shadowJar
    compileOnly(files("../test-server/plugins/[占位符]PlaceholderAPI-2.12.3.jar"))
}

// 工具链由根构建脚本统一配置（Java 21 编译 / release 21 字节码），此处不再覆盖。

// 把版本号注入 plugin.yml（plugin.yml 中用 ${version} 占位）
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// 原始 jar 标记为 plain，shadowJar 产出最终构件
tasks.jar {
    archiveClassifier = "plain"
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "cc-core"
    // Shadow 8.1.1 的重定位器不能读取 Java 21 字节码，因此不执行 relocate。
    // Paper 的插件类加载器负责隔离 HikariCP；sqlite-jdbc 含 JNI 本地库，同样禁止重定位。
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
