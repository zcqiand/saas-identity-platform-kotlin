package io.xr.harness

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionsLogoutTest {

  private lateinit var db: R2dbcDatabase
  private lateinit var cfg: AppConfig

  @BeforeAll
  fun setUp() {
    ScratchDb.prepare()
    db = ScratchDb.r2dbcDb()
    cfg =
        AppConfig(
            5109,
            ScratchDb.scratchPgUrl,
            "dev-key-32-bytes-minimum-length!",
            "saas-identity-platform",
            "saas-identity-platform-clients",
            3600,
        )
  }

  @AfterAll
  fun tearDown() {
    ScratchDb.drop()
  }

  @Test
  @Fn(["M01.F04.I06"])
  fun logoutIsNoOpSuccess() {
    // Review Focus 3：只断言 2xx；确切码若非 204/序列化异常 → 停下报告（生成模板层，人裁）
    var status: HttpStatusCode? = null
    runBlocking {
      testApplication {
        application { harnessModule(cfg) }
        status = client.post("/api/v1/auth/logout").status
      }
    }
    assertTrue(status?.isSuccess() == true, "logout 应 2xx，实际 $status")
  }
}
