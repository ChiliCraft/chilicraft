// cc-soul：灵魂/死亡附属（死亡结算 / 灵魂碎片 / 葬礼 / 12 遗物 / 物品流转史）
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
