// cc-adventure：地城 / 远征 / 世界 Boss 附属（冒险线）
plugins {
    id("java-library")
}

dependencies {
    // 核心 API：运行时由服务端上已安装的 cc-core 提供，不打进 jar
    compileOnly(project(":cc-core"))
    // 服务端 API：运行时由服务端提供，不打进 jar
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 日志门面：服务端自带，不打进 jar
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    // MythicMobs：可选托管世界 Boss 技能（运行时缺失自动降级原版属性改造，不打进 jar）
    compileOnly(files("../test-server/plugins/[神话生物]MythicMobs-5.13.0.jar"))
}

// 无运行时依赖需要打包，普通 jar 即最终构件（不需要 shadow）

// 把版本号注入 plugin.yml（plugin.yml 中用 ${version} 占位）
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
