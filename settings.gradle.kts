rootProject.name = "chilicraft"

plugins {
    // Toolchain 解析器：当本机找不到 JDK 21 时自动从 Foojay 下载
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

// 子工程：cc-core + 附属模块（后续剩余附属在此追加）
include("cc-core")
include("cc-survival")
include("cc-demon")
include("cc-soul")
include("cc-martial")
include("cc-adventure")
include("cc-season")
include("cc-quest")

