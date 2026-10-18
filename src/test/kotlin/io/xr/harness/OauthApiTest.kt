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
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
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

  /**
   * 5.112② 证据通道：同签名/iss/aud、有 sub 但**不带 tenant_id claim** 的 token——生产签发器 恒带该
   * claim（JwtIssuer.issueAccessToken），claim-miss 分支只能手工造形态经 HTTP 钉死。
   */
  private fun claimlessToken(userId: Uuid): String {
    val epoch = System.currentTimeMillis()
    return io.jsonwebtoken.Jwts.builder()
        .issuer(cfg.jwtIssuer)
        .audience()
        .add(cfg.jwtAudience)
        .and()
        .subject(userId.toString())
        .id(Uuid.random().toString())
        .issuedAt(java.util.Date(epoch - 1000))
        .notBefore(java.util.Date(epoch - 1000))
        .expiration(java.util.Date(epoch + cfg.jwtTtlSeconds * 1000))
        .signWith(javax.crypto.spec.SecretKeySpec(cfg.jwtSigningKey.toByteArray(), "HmacSHA256"))
        .compact()
  }

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

  /** 已知 client 但无 Bearer → 401（原误名 authorizeMissingTenantClaimIs401 拨正：没碰 claim 分支）。 */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeMissingBearerIs401() {
    val resp =
        authorize(
            null,
            """{"clientId":"saas-console","redirectUri":"$redirect",""" +
                """"responseType":"code","state":"s"}""",
        )
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  /**
   * 真 claim-miss：合法签名 + 有 sub 但**无 tenant_id claim** → 401（5.112② 证据通道）。 该 token 必过
   * requireBearer（decode 成功），401 只能出自 oauth_impl 的 「JWT tenant_id claim
   * required」分支（OauthApiServiceImpl:48-49）——分支互斥即证据。 message 透传断言（5.112②③ 终审 defer minor
   * 收口）：springboot e.getMessage() / rails e.message / fastapi exc.message 三 peer 全透传具体
   * message，kotlin 收敛固定 "invalid credentials" 是超集漂移——对齐家族口径。
   */
  @Test
  @Fn(["M04.F03.I01"])
  fun authorizeClaimlessTokenIs401() {
    val resp =
        authorize(
            claimlessToken(ScratchDb.ALICE),
            """{"clientId":"saas-console","redirectUri":"$redirect",""" +
                """"responseType":"code","state":"s"}""",
        )
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    val err = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals("INVALID_CREDENTIALS", err["code"]!!.jsonPrimitive.content)
    assertEquals(
        "JWT tenant_id claim required for authorize",
        err["message"]!!.jsonPrimitive.content,
    )
    val rows = runBlocking { codeRows() }
    assertEquals(0, rows.size)
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

  /** token 族（M04.F03.I02；fastapi oauth_impl.py token 镜像——code 一次性消费防重放）。 */
  private suspend fun exchangeToken(bearer: String?, body: String): Reply = withApp { client ->
    val r =
        client.post("/api/v1/oauth/token") {
          if (bearer != null) header("Authorization", "Bearer $bearer")
          contentType(ContentType.Application.Json)
          setBody(body)
        }
    Reply(r.status, r.bodyAsText())
  }

  /** authorize 签一个 code（走真实链路）。 */
  private suspend fun issueCode(
      bearer: String? = aliceToken(),
      redirectUri: String = redirect,
      scope: String = "read write",
  ): String {
    val resp =
        authorize(
            bearer,
            """{"clientId":"saas-console","redirectUri":"$redirectUri",""" +
                """"responseType":"code","state":"s","scope":"$scope"}""",
        )
    assertEquals(HttpStatusCode.OK, resp.status)
    return Json.parseToJsonElement(resp.body).jsonObject["code"]!!.jsonPrimitive.content
  }

  private suspend fun tableCount(table: org.jetbrains.exposed.v1.core.Table): Long =
      suspendTransaction(db) { table.selectAll().toList().size.toLong() }

  /** 全形状：code→token 交换，JWT 可验签 + scope 空格化 + code 行消费清空 + token 对落库。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenAuthorizationCodeExchangeReturnsFullShape() {
    val code = runBlocking { issueCode() }
    val resp = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"authorization_code","clientId":"saas-console",""" +
              """"code":"$code","redirectUri":"$redirect"}""",
      )
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    val access = obj["accessToken"]!!.jsonPrimitive.content
    val claims = jwt.decode(access)
    assertEquals(ScratchDb.ALICE.toString(), claims.subject)
    assertEquals(ScratchDb.TENANT1.toString(), claims.get("tenant_id"))
    assertTrue(obj["refreshToken"]!!.jsonPrimitive.content.startsWith("rt_"))
    assertEquals("Bearer", obj["tokenType"]!!.jsonPrimitive.content)
    assertEquals(3600, obj["expiresIn"]!!.jsonPrimitive.content.toInt())
    assertEquals(ScratchDb.ALICE.toString(), obj["userId"]!!.jsonPrimitive.content)
    assertEquals("saas-console", obj["clientId"]!!.jsonPrimitive.content)
    assertEquals(ScratchDb.TENANT1.toString(), obj["tenantId"]!!.jsonPrimitive.content)
    assertEquals("read write", obj["scope"]!!.jsonPrimitive.content)
    // 一次性消费：code 行清空；token 对落库各 +1
    assertEquals(0, runBlocking { tableCount(OauthCodeTable) })
    assertEquals(
        1,
        runBlocking { tableCount(saas.identity.entity.OauthAccessTokenTable) },
    )
    assertEquals(
        1,
        runBlocking { tableCount(saas.identity.entity.OauthRefreshTokenTable) },
    )
  }

  /** 同 code 二次 exchange → 400 INVALID_GRANT（一次性）。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenAuthorizationCodeIsOneTime() {
    val code = runBlocking { issueCode() }
    val body =
        """{"grantType":"authorization_code","clientId":"saas-console",""" +
            """"code":"$code","redirectUri":"$redirect"}"""
    assertEquals(HttpStatusCode.OK, runBlocking { exchangeToken(null, body) }.status)
    val replay = runBlocking { exchangeToken(null, body) }
    assertEquals(HttpStatusCode.BadRequest, replay.status)
    assertTrue(replay.body.contains("INVALID_GRANT"))
  }

  /** 过期 code → 400 且行已删。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenExpiredCodeIs400AndRowDeleted() {
    val expiredCode = "ac_expired-${Uuid.random()}"
    runBlocking {
      suspendTransaction(db) {
        OauthCodeTable.insert { st ->
          st[id] = Uuid.random()
          st[OauthCodeTable.code] = expiredCode
          st[OauthCodeTable.clientId] = ScratchDb.CLIENT_ID
          st[OauthCodeTable.userId] = ScratchDb.ALICE
          st[OauthCodeTable.tenantId] = ScratchDb.TENANT1
          st[OauthCodeTable.redirectUri] = redirect
          st[OauthCodeTable.scope] = "read write"
          st[OauthCodeTable.expiresAt] = OffsetDateTime.now().minusMinutes(1)
          st[OauthCodeTable.createdAt] = OffsetDateTime.now().minusMinutes(6)
        }
      }
    }
    val resp = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"authorization_code","clientId":"saas-console",""" +
              """"code":"$expiredCode","redirectUri":"$redirect"}""",
      )
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertTrue(resp.body.contains("INVALID_GRANT"))
    assertEquals(0, runBlocking { tableCount(OauthCodeTable) })
  }

  /** redirect 不匹配 → 400 且 code 烧毁（重放也 400）。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenRedirectMismatchIs400AndCodeBurned() {
    val code = runBlocking { issueCode() }
    val bad =
        """{"grantType":"authorization_code","clientId":"saas-console",""" +
            """"code":"$code","redirectUri":"http://evil.example.com/callback"}"""
    val resp = runBlocking { exchangeToken(null, bad) }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertEquals(0, runBlocking { tableCount(OauthCodeTable) })
    val replay = runBlocking { exchangeToken(null, bad) }
    assertEquals(HttpStatusCode.BadRequest, replay.status)
  }

  /** 未知 client → 400 INVALID_CLIENT。 */
  @Test
  fun tokenUnknownClientIs400() {
    val resp = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"authorization_code","clientId":"no-such",""" +
              """"code":"ac_x","redirectUri":"$redirect"}""",
      )
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertTrue(resp.body.contains("INVALID_CLIENT"))
  }

  /** 缺 code / 缺 redirectUri → 400 INVALID_REQUEST。 */
  @Test
  fun tokenMissingCodeOrRedirectIs400() {
    val noCode = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"authorization_code","clientId":"saas-console",""" +
              """"redirectUri":"$redirect"}""",
      )
    }
    assertEquals(HttpStatusCode.BadRequest, noCode.status)
    assertTrue(noCode.body.contains("INVALID_REQUEST"))
    val noRedirect = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"authorization_code","clientId":"saas-console",""" + """"code":"ac_x"}""",
      )
    }
    assertEquals(HttpStatusCode.BadRequest, noRedirect.status)
    assertTrue(noRedirect.body.contains("INVALID_REQUEST"))
  }

  /** 契约外 grant（枚举反序列化面）→ 400。 */
  @Test
  fun tokenUnsupportedGrantIs400() {
    val resp = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"password","clientId":"saas-console",""" +
              """"username":"alice","password":"dev123456"}""",
      )
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
  }

  /** rotate 族（M04.F03.I02；fastapi oauth_impl.py refresh 镜像——撤旧签新，client 归属钉 rt 行）。 */
  private suspend fun rotate(refreshToken: String, clientId: String = "saas-console"): Reply =
      runBlocking {
        exchangeToken(
            null,
            """{"grantType":"refresh_token","clientId":"$clientId","refreshToken":"$refreshToken"}""",
        )
      }

  /** authorize→exchange 全链路拿 rt1。 */
  private suspend fun exchangedRefresh(scope: String = "read write"): Pair<String, String> {
    val code = runBlocking { issueCode(scope = scope) }
    val resp = runBlocking {
      exchangeToken(
          null,
          """{"grantType":"authorization_code","clientId":"saas-console",""" +
              """"code":"$code","redirectUri":"$redirect"}""",
      )
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    return Pair(
        obj["refreshToken"]!!.jsonPrimitive.content,
        obj["accessToken"]!!.jsonPrimitive.content,
    )
  }

  /** rotate 撤旧签新：rt2≠rt1、新 JWT 可验签、DB 旧行 revoked=true 新行 false。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenRefreshRotatesAndRevokesOld() {
    val (rt1, _) = runBlocking { exchangedRefresh() }
    val resp = runBlocking { rotate(rt1) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    val rt2 = obj["refreshToken"]!!.jsonPrimitive.content
    assertTrue(rt2 != rt1)
    val claims = jwt.decode(obj["accessToken"]!!.jsonPrimitive.content)
    assertEquals(ScratchDb.ALICE.toString(), claims.subject)
    val rtRows = runBlocking {
      suspendTransaction(db) {
        saas.identity.entity.OauthRefreshTokenTable.selectAll().toList().associate {
          it[saas.identity.entity.OauthRefreshTokenTable.refreshToken] to
              it[saas.identity.entity.OauthRefreshTokenTable.revoked]
        }
      }
    }
    assertEquals(true, rtRows[rt1])
    assertEquals(false, rtRows[rt2])
  }

  /** 已撤销 rt1 复用 → 400 INVALID_GRANT。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenRotatedOldRefreshIs400() {
    val (rt1, _) = runBlocking { exchangedRefresh() }
    assertEquals(HttpStatusCode.OK, runBlocking { rotate(rt1) }.status)
    val replay = runBlocking { rotate(rt1) }
    assertEquals(HttpStatusCode.BadRequest, replay.status)
    assertTrue(replay.body.contains("INVALID_GRANT"))
  }

  /** client 归属钉旧 rt 行绑定值：请求体塞 second-app 也回 saas-console。 */
  @Test
  @Fn(["M04.F03.I02"])
  fun tokenRotateClientIdFromRtRowNotRequestBody() {
    val (rt1, _) = runBlocking { exchangedRefresh() }
    val resp = runBlocking { rotate(rt1, clientId = "second-app") }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals("saas-console", obj["clientId"]!!.jsonPrimitive.content)
    val rt2 = obj["refreshToken"]!!.jsonPrimitive.content
    val newClient = runBlocking {
      suspendTransaction(db) {
        saas.identity.entity.OauthRefreshTokenTable.selectAll()
            .where { saas.identity.entity.OauthRefreshTokenTable.refreshToken eq rt2 }
            .singleOrNull()
            ?.get(saas.identity.entity.OauthRefreshTokenTable.clientId)
      }
    }
    assertEquals("saas-console", newClient)
  }

  /** 未知 rt → 400。 */
  @Test
  fun tokenRotateUnknownRefreshIs400() {
    val resp = runBlocking { rotate("rt_nonexistent") }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertTrue(resp.body.contains("INVALID_GRANT"))
  }

  /** scope 从旧 rt 关联 access 行携带到新对。 */
  @Test
  fun tokenRotateScopeCarriedFromAccessRow() {
    val (rt1, _) = runBlocking { exchangedRefresh(scope = "read write") }
    val resp = runBlocking { rotate(rt1) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals("read write", obj["scope"]!!.jsonPrimitive.content)
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
