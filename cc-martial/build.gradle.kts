// cc-martial：武学/擂台附属（6 境界 / 5 流派 / 主动被动技能 / 45 词缀 / 每日擂台）
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

    // 外部联动软依赖：编译期取本地测试服同版本 jar，运行时全部可选（缺失自动降级）
    // PlaceholderAPI：境界/流派/词缀变量暴露
    compileOnly(files("../test-server/plugins/[占位符]PlaceholderAPI-2.12.3.jar"))
    // Vault：擂台奖励可选金钱加成
    compileOnly(files("../test-server/plugins/[经济桥]Vault-1.7.3.jar"))
    // MythicMobs：流派武品（MM 物品）与技能特效托管
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
