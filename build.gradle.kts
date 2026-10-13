plugins {
  kotlin("jvm") version "2.4.21"
  id("com.ncorti.ktfmt.gradle") version "0.27.0"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
  application
}

group = "io.xr"

version = "0.1.0"

repositories { mavenCentral() }

dependencies {
  implementation("io.ktor:ktor-server-netty:3.6.0")
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
}
