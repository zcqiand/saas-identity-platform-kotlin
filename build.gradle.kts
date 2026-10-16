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
  // 实体层（ADR-0046 DB-First）：exposed-bom 收口禁裸写版本；r2dbc 驱动 BOM 外钉 exact。
  // core=Table DSL；kotlin-datetime=timestamp/date 系（包名 v1.datetime，无 .kotlin 段）；
  // json=jsonb；r2dbc=Phase 3 挂 Ktor 协程。
  implementation(platform("org.jetbrains.exposed:exposed-bom:1.5.1"))
  implementation("org.jetbrains.exposed:exposed-core")
  implementation("org.jetbrains.exposed:exposed-kotlin-datetime")
  implementation("org.jetbrains.exposed:exposed-json")
  implementation("org.postgresql:r2dbc-postgresql:1.1.3.RELEASE")
  // route 缝委托（Phase 3）：generator delegatePattern 生成的 interface + call.delegates 基建
  // 都在生成区；这里只补业务侧依赖。content-negotiation=DTO JSON 收发；status-pages=异常映射。
  implementation("io.ktor:ktor-server-content-negotiation")
  implementation("io.ktor:ktor-serialization-kotlinx-json")
  implementation("io.ktor:ktor-server-status-pages")
  // R2DBC suspend 查询（ADR-0046 预告路径）：exposed-r2dbc BOM 收口；池件 BOM 外钉 exact。
  implementation("org.jetbrains.exposed:exposed-r2dbc")
  implementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
  // JWT HS256 签发（jjwt 0.12.x fluent API）；impl/jackson runtime-only。
  implementation("io.jsonwebtoken:jjwt-api:0.12.6")
  runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
  runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
  // 测试 scratch 库 admin DDL（DROP/CREATE DATABASE 不能跑在事务内，R2DBC 事务壳不适合；JDBC 直连）
  testImplementation("org.postgresql:postgresql:42.7.7")
  testImplementation("io.ktor:ktor-server-test-host")
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
  // 生成区禁手改（gen-shared.sh / scaffold-entities.sh 产物），不进静态门
  // （fastapi ruff extend-exclude 同款纪律）。显式枚举 source 是为了把排除精确钉在
  // 生成树 saas/identity/shared + saas/identity/entity 上——test 树照常全覆盖，不许陪葬。
  // 一棵树布局（ADR-0045 §2 修订）：整树排除 = 整树不误伤。
  // 占位符由 init_project.py 按仓名前缀渲染成本家族生成区路径（family_pkg_dir /
  // entity_pkg_dir）。本块保持 ktfmt 正形（单行 lambda）——渲染产物即门内合法形态，
  // --sync 后无需 format 复位。
  source.setFrom(
      fileTree("src/main/kotlin") { exclude("saas/identity/shared/**", "saas/identity/entity/**") },
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
  exclude("saas/identity/entity/**")
}
