plugins {
  kotlin("jvm") version "2.4.21"
  kotlin("plugin.serialization") version "2.4.21"
  id("com.ncorti.ktfmt.gradle") version "0.27.0"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
  application
}

group = "io.xr"

version = "0.1.0"

repositories { mavenCentral() }

dependencies {
  // 生成区依赖收口 ktor-bom（ADR-0045 依赖清单）：版本一处管，禁裸写。
  // 缺一不可：core=生成缝 import 面；auth=io.ktor.server.auth.*；
  // resources=Paths.kt 资源类 + Route.resources 扩展；serialization=@Serializable DTO。
  implementation(platform("io.ktor:ktor-bom:3.6.0"))
  implementation("io.ktor:ktor-server-netty")
  implementation("io.ktor:ktor-server-core")
  implementation("io.ktor:ktor-server-auth")
  implementation("io.ktor:ktor-server-resources")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  // trace listener 直接 import platform API（TestExecutionListener/TestIdentifier/TestPlan）。
  // JUnit 5.11+/6 起 platform 是 jupiter 的非传递依赖，Gradle 9 也不再自动提供 launcher——
  // 必须显式声明，版本由 junit-bom 管。
  testImplementation("org.junit.platform:junit-platform-engine")
  testImplementation("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("io.xr.harness.ApplicationKt") }

tasks.test {
  useJUnitPlatform()
  // trace_cmd 的 -Dharness.trace=true 落在 Gradle JVM；显式传给测试 JVM
  systemProperty("harness.trace", System.getProperty("harness.trace"))
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
  // 生成区禁手改（gen-shared.sh 产物），不进静态门（fastapi ruff extend-exclude 同款纪律）。
  // 显式枚举 source 是为了把排除精确钉在生成树 saas/identity/shared 上——test 树照常全覆盖，
  // 不许陪葬。一棵树布局（ADR-0045 §2 修订）：整树排除 = 整树不误伤。
  // 占位符由 init_project.py 按仓名前缀渲染成本家族生成区路径（family_pkg_dir）。
  source.setFrom(
      fileTree("src/main/kotlin") { exclude("saas/identity/shared/**") },
      fileTree("src/test/kotlin"),
  )
}

ktfmt {
  // 生成区不进格式门（fastapi ruff extend-exclude 同款纪律）。
}

// ktfmt 0.27.0 的 srcSetPathExclusionPattern 实证只认 source set 路径、对文件级排除无效
// （scratch 双区测试：66 件生成区全被标、零排除）。真通道 = KtfmtBaseTask extends
// SourceTask → 任务级 exclude。生成区先删后写自愈，scratch 探针可证伪。
tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
  exclude("saas/identity/shared/**")
}
