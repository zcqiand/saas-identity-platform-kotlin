package io.xr.harness

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import saas.identity.entity.OauthCodeTable
import saas.identity.impl.JwtIssuer

/** oauth authorize（M04.F03.I01；fastapi oauth_impl.py:95-103 镜像——client 400 先于 Bearer 401）。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OauthApiTest {

  private lateinit var db: R2dbcDatabase
  private lateinit var cfg: AppConfig
  private lateinit var jwt: JwtIssuer
  private val redirect = "http://localhost:5101/callback"

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

  private fun <T : Any> withApp(block: suspend (io.ktor.client.HttpClient) -> T): T {
    lateinit var result: T
    runBlocking {
      testApplication {
        application { harnessModule(cfg) }
        result = block(client)
      }
    }
    return result
  }

  /** bearer=null → 不发 Authorization 头（用例 2/3 顺序语义要用）。 */
  private fun authorize(
      bearer: String?,
      body: String,
  ): Reply = withApp { client ->
    val r =
        client.post("/api/v1/oauth/authorize") {
          if (bearer != null) header("Authorization", "Bearer $bearer")
          contentType(ContentType.Application.Json)
          setBody(body)
        }
    Reply(r.status, r.bodyAsText())
  }

  private fun aliceToken(): String = jwt.issueAccessToken(ScratchDb.ALICE, ScratchDb.TENANT1)

  private suspend fun codeRows(): List<org.jetbrains.exposed.v1.core.ResultRow> =
      suspendTransaction(db) { OauthCodeTable.selectAll().toList() }

  private suspend fun codeByCode(code: String): org.jetbrains.exposed.v1.core.ResultRow? =
      suspendTransaction(db) {
        OauthCodeTable.selectAll().where { OauthCodeTable.code eq code }.singleOrNull()
      }

  /** 正常签发：code 前缀 + state 回显 + 落库行（scope/redirect/expires≈now+5min）。 */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeIssuesOneTimeCodeWithStateEcho() {
    val resp =
        authorize(
            aliceToken(),
            """{"clientId":"saas-console","redirectUri":"$redirect",""" +
                """"responseType":"code","state":"st-1","scope":"read write"}""",
        )
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    val code = obj["code"]!!.jsonPrimitive.content
    assertTrue(code.startsWith("ac_"))
    assertEquals("st-1", obj["state"]!!.jsonPrimitive.content)
    val rows = runBlocking { codeRows() }
    assertEquals(1, rows.size)
    val row = runBlocking { codeByCode(code) }!!
    assertEquals("read write", row[OauthCodeTable.scope])
    assertEquals(redirect, row[OauthCodeTable.redirectUri])
    val expires = row[OauthCodeTable.expiresAt]
    val now = OffsetDateTime.now()
    assertTrue(expires.isAfter(now.plusMinutes(4)) && expires.isBefore(now.plusMinutes(6)))
    assertEquals(ScratchDb.ALICE, row[OauthCodeTable.userId])
  }

  /** 顺序 Ruling：client 查找（400）先于 Bearer 校验（401）——无头 + 未知 client 也是 400。 */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeUnknownClientIs400EvenWithoutBearer() {
    val resp =
        authorize(
            null,
            """{"clientId":"no-such","redirectUri":"$redirect",""" +
                """"responseType":"code","state":"s"}""",
        )
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertTrue(resp.body.contains("client_id"))
  }

  /** 已知 client 但无 Bearer → 401。 */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeMissingTenantClaimIs401() {
    val resp =
        authorize(
            null,
            """{"clientId":"saas-console","redirectUri":"$redirect",""" +
                """"responseType":"code","state":"s"}""",
        )
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  /** redirect 白名单：精确匹配 + 前缀+'?'（query 后缀）都收。 */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeRedirectExactMatchAcceptedQuerySuffixAllowed() {
    val resp =
        authorize(
            aliceToken(),
            """{"clientId":"saas-console","redirectUri":"$redirect?next=%2Fdash",""" +
                """"responseType":"code","state":"s"}""",
        )
    assertEquals(HttpStatusCode.OK, resp.status)
  }

  /** 子路径伪装 → 400 INVALID_REDIRECT_URI。 */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeRedirectSubpathRejected() {
    val resp =
        authorize(
            aliceToken(),
            """{"clientId":"saas-console","redirectUri":"http://localhost:5101/callback/evil",""" +
                """"responseType":"code","state":"s"}""",
        )
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertTrue(resp.body.contains("INVALID_REDIRECT_URI"))
  }

  /** state 原样回显（空串/特殊字符）。 */
  @Test
  fun authorizeStateAlwaysEchoed() {
    val empty =
        authorize(
            aliceToken(),
            """{"clientId":"saas-console","redirectUri":"$redirect",""" +
                """"responseType":"code","state":""}""",
        )
    assertEquals(
        "",
        Json.parseToJsonElement(empty.body).jsonObject["state"]!!.jsonPrimitive.content,
    )
    val weird =
        authorize(
            aliceToken(),
            """{"clientId":"saas-console","redirectUri":"$redirect",""" +
                """"responseType":"code","state":"a b&c=中"}""",
        )
    assertEquals(
        "a b&c=中",
        Json.parseToJsonElement(weird.body).jsonObject["state"]!!.jsonPrimitive.content,
    )
    val rows = runBlocking { codeRows() }
    assertEquals(2, rows.size)
  }
}
