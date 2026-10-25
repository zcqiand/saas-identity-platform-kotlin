package io.xr.harness

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Swagger 文档面（REQ-2026-003）：基础设施端点，不进功能树（无 fn 挂载，与 /health 同模式）。 走 ScratchDb
 * 家族模式——R2dbcDatabase.connect 是 eager 校验，惰性假设不成立（本类初版踩坑）； 文档三路由本身不消费 DB。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocSurfaceTest {

  private lateinit var cfg: AppConfig

  @BeforeAll
  fun setUp() {
    ScratchDb.prepare()
    cfg =
        AppConfig(
            serverPort = 5109,
            databaseUrl = ScratchDb.scratchPgUrl,
            jwtSigningKey = "dev-key-32-bytes-minimum-length!",
            jwtIssuer = "saas-identity-platform",
            jwtAudience = "saas-identity-platform-clients",
            jwtTtlSeconds = 3600,
        )
  }

  @AfterAll
  fun tearDown() {
    ScratchDb.drop()
  }

  private fun <T : Any> withApp(
      followRedirects: Boolean = true,
      block: suspend (io.ktor.client.HttpClient) -> T,
  ): T {
    lateinit var result: T
    kotlinx.coroutines.runBlocking {
      testApplication {
        // testApplication 默认 client 跟随重定向——钉 302 形状必须关掉
        val c = createClient { this.followRedirects = followRedirects }
        application { harnessModule(cfg) }
        result = block(c)
      }
    }
    return result
  }

  @Test
  fun rootRedirectsToApiDocs() =
      withApp(followRedirects = false) { client ->
        val res = client.get("/")
        assertEquals(HttpStatusCode.Found, res.status)
        assertEquals("/api-docs", res.headers[HttpHeaders.Location])
      }

  @Test
  fun docsServesSwaggerShell() = withApp { client ->
    val res = client.get("/api-docs")
    assertEquals(HttpStatusCode.OK, res.status)
    assertTrue(res.bodyAsText().contains("SwaggerUIBundle"))
  }

  @Test
  fun openapiYamlServesSharedContract() = withApp { client ->
    val res = client.get("/api-docs/openapi.yaml")
    assertEquals(HttpStatusCode.OK, res.status)
    assertTrue(res.bodyAsText().trimEnd().startsWith("openapi:"))
  }

  @Test
  fun swaggerUiWebjarServesBundle() = withApp { client ->
    // 版本段走 URL（basePackage 点分指纹：版本号里的点会被劈成 5/34/0）
    val res = client.get("/swagger-ui/5.34.0/swagger-ui.css")
    assertEquals(HttpStatusCode.OK, res.status)
  }
}
