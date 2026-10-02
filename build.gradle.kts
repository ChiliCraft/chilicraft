// ChiliCraft 根构建脚本：统一声明版本、Java 工具链与仓库

allprojects {
    group = "com.chilicraft"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")

    // 统一工具链：Java 21 编译（目标字节码由下方 options.release = 21 保证）。
    // 不用 JDK 17 工具链的原因：中文 Windows（GBK）下，JDK 17 worker 进程
    // 按平台编码读取 daemon 写出的 UTF-8 类路径 argfile，含非 ASCII 字符的
    // GRADLE_USER_HOME 路径会被读成乱码，导致 GradleWorkerMain 加载失败；
    // JDK 18+ 启动器默认按 UTF-8 读取，与本机默认 JDK 21 daemon 一致。
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        // 显式暴露过时 API 用法，防止随服务端版本升级悄悄失效
        options.compilerArgs.add("-Xlint:deprecation")
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
        // Paper API 仓库（国内网络需走代理，见 ~/.gradle/gradle.properties）
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

// 汇总产物：各模块最终 jar + test-server/plugins 下的运行时软依赖，便于整体部署。
// 软依赖在模块里是 compileOnly，不会进模块 jar；部署时它们仍需单独安装到服务端
// plugins/ 目录，因此这里一并输出。CI 会先补齐这些 jar 再调用本任务。
tasks.register<Sync>("dist") {
    group = "build"
    description = "汇总所有模块 jar 与 test-server/plugins 下的软依赖到 build/dist"

    subprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "build" })
    }

    from(subprojects.map { it.layout.buildDirectory.dir("libs") }) {
        include("*.jar")
        // cc-core 的原始 jar（*-plain.jar）不对外，只保留 shadowJar
        exclude("*-plain.jar")
    }
    from(layout.projectDirectory.dir("test-server/plugins")) {
        include("*.jar")
    }
    into(layout.buildDirectory.dir("dist"))
}
