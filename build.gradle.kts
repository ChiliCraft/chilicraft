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
