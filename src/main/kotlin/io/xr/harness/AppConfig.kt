package io.xr.harness

/** 运行配置：main 从 env 组装（fail-fast 禁兜底），测试直接构造（scratch 指向）。 */
data class AppConfig(
    val serverPort: Int,
    val databaseUrl: String,
    val jwtSigningKey: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtTtlSeconds: Long,
)

fun appConfigFromEnv(): AppConfig {
  fun req(name: String): String =
      System.getenv(name) ?: error("$name 未设置。禁止默认值兜底（suite-hard-rules §1）；dev 值见 .env.example。")
  val port = req("SERVER_PORT").toIntOrNull() ?: error("SERVER_PORT 未设置或不是整数。")
  val ttl = req("JWT_TTL_SECONDS").toLongOrNull() ?: error("JWT_TTL_SECONDS 未设置或不是整数。")
  return AppConfig(
      serverPort = port,
      databaseUrl = req("DATABASE_URL"),
      jwtSigningKey = req("JWT_SIGNING_KEY"),
      jwtIssuer = req("JWT_ISSUER"),
      jwtAudience = req("JWT_AUDIENCE"),
      jwtTtlSeconds = ttl,
  )
}

/** postgresql:// → r2dbc:postgresql://（家族 env 单串同形；映射属配置解析，非兜底）。 */
fun String.toR2dbcUrl(): String = replaceFirst("postgresql://", "r2dbc:postgresql://")
