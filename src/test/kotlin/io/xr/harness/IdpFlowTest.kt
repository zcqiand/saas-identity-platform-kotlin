package io.xr.harness

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import saas.identity.entity.OauthClientTable
import saas.identity.impl.JwtIssuer

/**
 * lab kotlin 消费全链钉（M04.F03.I01/I02 + M01.F03.I01）：login → authorize（lab-management client 形态）→
 * token 交换 → 新 access 打 me/tenants。四跳一气呵成，链路断裂即红。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdpFlowTest {

  private lateinit var db: R2dbcDatabase
  private lateinit var cfg: AppConfig
  private lateinit var jwt: JwtIssuer
  private val labRedirect = "http://localhost:5209/callback"

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

  /** lab-management client 行（IdP 侧白名单；lab 家族 SSO 接线 clientId）。 */
  private suspend fun insertLabClient() {
    suspendTransaction(db) {
      OauthClientTable.insert {
        it[id] = Uuid.parse("55555555-5555-5555-5555-555555555555")
        it[clientId] = "lab-management"
        it[clientSecret] = "plain:lab-secret"
        it[clientName] = "lab management"
        it[grantTypes] = "authorization_code,refresh_token"
        it[redirectUris] = labRedirect
        it[scopes] = null
        it[accessTokenValidity] = 3600
        it[refreshTokenValidity] = 2592000
        it[autoApprove] = false
        it[status] = 1
        it[createdAt] = OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
        it[updatedAt] = OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
      }
    }
  }

  @Test
  @Fn(["M04.F03.I01", "M04.F03.I02", "M01.F03.I01"])
  fun labConsumerFullChainLoginAuthorizeTokenMeTenants() {
    lateinit var chain: String
    runBlocking {
      insertLabClient()
      testApplication {
        application { harnessModule(cfg) }
        // 1. login（真链路取 user token）
        val userToken = loginAsAlice(client)
        // 2. authorize（lab-management 形态，redirect 带白名单 query 后缀）
        val code = authorizeLabClient(client, userToken)
        // 3. token 交换（code 一次性）
        val tokenObj = exchangeLabCode(client, code)
        val newAccess = tokenObj["accessToken"]!!.jsonPrimitive.content
        assertEquals("lab-management", tokenObj["clientId"]!!.jsonPrimitive.content)
        assertEquals("read", tokenObj["scope"]!!.jsonPrimitive.content)
        assertEquals(
            ScratchDb.TENANT1.toString(),
            jwt.decode(newAccess).get("tenant_id"),
        )
        // 4. 新 access 打 me/tenants → TENANT1+TENANT2 固定序
        val tenants = meTenants(client, newAccess)
        assertEquals(2, tenants.size)
        assertEquals(
            ScratchDb.TENANT1.toString(),
            tenants[0].jsonObject["tenantId"]!!.jsonPrimitive.content,
        )
        assertEquals(
            ScratchDb.TENANT2.toString(),
            tenants[1].jsonObject["tenantId"]!!.jsonPrimitive.content,
        )
        chain = "login→authorize→token→me/tenants ok"
      }
    }
    assertTrue(chain.isNotEmpty())
  }

  /** 全链四步各自成 helper（步骤数=验收面，断言留在主用例内）。 */
  private suspend fun loginAsAlice(client: io.ktor.client.HttpClient): String {
    val resp =
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody("""{"username":"alice","password":"dev123456","clientId":"saas-console"}""")
        }
    assertEquals(HttpStatusCode.OK, resp.status)
    return Json.parseToJsonElement(resp.bodyAsText())
        .jsonObject["accessToken"]!!
        .jsonPrimitive
        .content
  }

  private suspend fun authorizeLabClient(
      client: io.ktor.client.HttpClient,
      userToken: String,
  ): String {
    val resp =
        client.post("/api/v1/oauth/authorize") {
          header("Authorization", "Bearer $userToken")
          contentType(ContentType.Application.Json)
          setBody(
              """{"clientId":"lab-management","redirectUri":"$labRedirect?next=%2Fdashboard",""" +
                  """"responseType":"code","state":"lab-flow","scope":"read"}""",
          )
        }
    assertEquals(HttpStatusCode.OK, resp.status)
    return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
  }

  private suspend fun exchangeLabCode(
      client: io.ktor.client.HttpClient,
      code: String,
  ): kotlinx.serialization.json.JsonObject {
    val resp =
        client.post("/api/v1/oauth/token") {
          contentType(ContentType.Application.Json)
          setBody(
              """{"grantType":"authorization_code","clientId":"lab-management",""" +
                  """"code":"$code","redirectUri":"$labRedirect?next=%2Fdashboard"}""",
          )
        }
    assertEquals(HttpStatusCode.OK, resp.status)
    return Json.parseToJsonElement(resp.bodyAsText()).jsonObject
  }

  private suspend fun meTenants(
      client: io.ktor.client.HttpClient,
      accessToken: String,
  ): kotlinx.serialization.json.JsonArray {
    val resp = client.get("/api/v1/me/tenants") { header("Authorization", "Bearer $accessToken") }
    assertEquals(HttpStatusCode.OK, resp.status)
    return Json.parseToJsonElement(resp.bodyAsText()).jsonArray
  }
}
