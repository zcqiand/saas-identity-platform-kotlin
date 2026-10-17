package io.xr.harness

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import saas.identity.impl.JwtIssuer

/** M00.F01.I01 admin tenants list（fastapi tenants_impl.py:53-70 镜像；lab 消费形态）。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTenantsListTest {

  private lateinit var db: R2dbcDatabase
  private lateinit var cfg: AppConfig
  private lateinit var jwt: JwtIssuer

  @BeforeAll
  fun setUp() {
    ScratchDb.prepare()
    db = ScratchDb.r2dbcDb()
    cfg =
        AppConfig(
            serverPort = 5109,
            databaseUrl = ScratchDb.scratchPgUrl,
            jwtSigningKey = "dev-key-32-bytes-minimum-length!",
            jwtIssuer = "saas-identity-platform",
            jwtAudience = "saas-identity-platform-clients",
            jwtTtlSeconds = 3600,
        )
    jwt = JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtAudience, cfg.jwtTtlSeconds)
  }

  @AfterAll
  fun tearDown() {
    ScratchDb.drop()
  }

  @BeforeEach fun reseed() = runBlocking { ScratchDb.truncateAndSeed(db) }

  private data class Reply(val status: HttpStatusCode, val body: String)

  /** rawHeader 非空时原样作 Authorization 值（null=不发头；""=Bearer 空串）。 */
  private fun getTenants(
      token: String? = null,
      query: String = "",
      rawHeader: String? = null,
  ): Reply {
    lateinit var reply: Reply
    runBlocking {
      testApplication {
        application { harnessModule(cfg) }
        val resp =
            client.get("/api/v1/admin/tenants$query") {
              when {
                rawHeader != null -> header("Authorization", rawHeader)
                token != null -> header("Authorization", "Bearer $token")
              }
            }
        reply = Reply(resp.status, resp.bodyAsText())
      }
    }
    return reply
  }

  /** lab 消费形态 page=0&pageSize=100：三租户全量回。 */
  @Test
  @Fn(["M00.F01.I01"])
  fun listTenantsLabConsumptionShape() {
    val resp =
        getTenants(jwt.issueAccessToken(ScratchDb.ALICE, ScratchDb.TENANT1), "?page=0&pageSize=100")
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals(3, obj["items"]!!.jsonArray.size)
    assertEquals(3, obj["total"]!!.jsonPrimitive.int)
    assertEquals(0, obj["page"]!!.jsonPrimitive.int)
    assertEquals(100, obj["pageSize"]!!.jsonPrimitive.int)
    // 读路径 status 恒 active（springboot 参照）
    assertEquals(
        "active",
        obj["items"]!!.jsonArray[2].jsonObject["status"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun listTenantsDefaultsPage0Size20() {
    val resp = getTenants(jwt.issueAccessToken(ScratchDb.ALICE, ScratchDb.TENANT1))
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals(0, obj["page"]!!.jsonPrimitive.int)
    assertEquals(20, obj["pageSize"]!!.jsonPrimitive.int)
    assertEquals(3, obj["items"]!!.jsonArray.size)
  }

  /** 设施面：无头 / 非 Bearer 前缀 / Bearer 空串三路 401（不挂 ID）。 */
  @Test
  fun listTenantsBearerBoundariesAre401() {
    assertEquals(HttpStatusCode.Unauthorized, getTenants().status)
    assertEquals(HttpStatusCode.Unauthorized, getTenants(rawHeader = "Basic abc").status)
    assertEquals(HttpStatusCode.Unauthorized, getTenants(token = "").status)
  }
}
