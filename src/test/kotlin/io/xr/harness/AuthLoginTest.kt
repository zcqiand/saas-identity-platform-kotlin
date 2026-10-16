package io.xr.harness

import io.jsonwebtoken.Jwts
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import saas.identity.entity.OauthAccessTokenTable
import saas.identity.entity.OauthRefreshTokenTable
import saas.identity.entity.SysUserTable
import saas.identity.entity.TenantMemberTable
import saas.identity.shared.models.ErrorResponse
import saas.identity.shared.models.LoginResponse

/** spec §4 登录十条逐条成测：9 个用例 + fixture helper，超出 detekt 单类函数阈值属测试类常态。 */
@Suppress("TooManyFunctions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthLoginTest {

  private lateinit var db: R2dbcDatabase
  private lateinit var cfg: AppConfig

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
  }

  @AfterAll
  fun tearDown() {
    ScratchDb.drop()
  }

  @BeforeEach fun reseed() = runBlocking { ScratchDb.truncateAndSeed(db) }

  /** status + body 快照（testApplication 块随 coroutinue 关闭，body 必须块内取）。 */
  private data class LoginReply(val status: HttpStatusCode, val body: String)

  private fun postLogin(body: String): LoginReply {
    lateinit var reply: LoginReply
    runBlocking {
      testApplication {
        application { harnessModule(cfg) }
        val resp =
            client.post("/api/v1/auth/login") {
              contentType(ContentType.Application.Json)
              setBody(body)
            }
        reply = LoginReply(resp.status, resp.bodyAsText())
      }
    }
    return reply
  }

  private fun loginBody(
      username: String,
      password: String,
      clientId: String = ScratchDb.CLIENT_ID,
  ) = """{"username":"$username","password":"$password","clientId":"$clientId"}"""

  private fun decodeLogin(reply: LoginReply): LoginResponse =
      Json.decodeFromString<LoginResponse>(reply.body)

  @Test
  @Fn(["M01.F04.I01"])
  fun loginHappyPathReturnsFamilyContractShape() {
    val resp = postLogin(loginBody("alice", "dev123456"))
    assertEquals(HttpStatusCode.OK, resp.status)
    val dto = decodeLogin(resp)
    assertEquals(ScratchDb.ALICE, dto.userId)
    assertEquals(ScratchDb.TENANT1, dto.currentTenantId)
    assertEquals("Bearer", dto.tokenType)
    assertEquals(3600, dto.expiresIn)
    // 订阅过滤：alice 有 T1/T2 两个 active membership，saas-console 只订阅 T1
    assertEquals(listOf(ScratchDb.TENANT1), dto.availableTenants.map { it.tenantId })
    assertEquals(listOf(ScratchDb.ROLE1.toString()), dto.availableTenants.single().roleIds)
    assertEquals("alice", dto.user.username)
    assertTrue(dto.accessToken?.isNotBlank() == true)
    assertTrue(dto.refreshToken?.startsWith("rt_") == true)
  }

  @Test
  @Fn(["M01.F04.I01"])
  fun accessTokenCarriesFamilyJwtClaims() {
    val dto = decodeLogin(postLogin(loginBody("alice", "dev123456")))
    val key = SecretKeySpec(cfg.jwtSigningKey.toByteArray(), "HmacSHA256")
    val claims =
        Jwts.parser()
            .verifyWith(key)
            .requireIssuer(cfg.jwtIssuer)
            .requireAudience(cfg.jwtAudience)
            .build()
            .parseSignedClaims(dto.accessToken!!)
            .payload
    assertEquals(ScratchDb.ALICE.toString(), claims.subject)
    assertEquals(ScratchDb.TENANT1.toString(), claims.get("tenant_id", String::class.java))
  }

  @Test
  fun tokenPairPersistedOneRowEach() = runBlocking {
    postLogin(loginBody("alice", "dev123456"))
    suspendTransaction(db) {
      assertEquals(1L, OauthAccessTokenTable.selectAll().count())
      assertEquals(1L, OauthRefreshTokenTable.selectAll().count())
    }
  }

  @Test
  fun wrongPasswordIs401SameSemanticsAsUnknownUser() {
    assertEquals(HttpStatusCode.Unauthorized, postLogin(loginBody("alice", "wrong")).status)
    // 未知用户同语义（不泄露存在性）
    val unknown = postLogin(loginBody("nobody", "whatever"))
    assertEquals(HttpStatusCode.Unauthorized, unknown.status)
    assertEquals("INVALID_CREDENTIALS", Json.decodeFromString<ErrorResponse>(unknown.body).code)
  }

  @Test
  @Fn(["M01.F04.I01"])
  fun fiveFailuresLocksAccountThen423EvenWithCorrectPassword() {
    repeat(5) {
      assertEquals(HttpStatusCode.Unauthorized, postLogin(loginBody("alice", "bad$it")).status)
    }
    val locked = postLogin(loginBody("alice", "dev123456"))
    assertEquals(HttpStatusCode.Locked, locked.status)
    assertEquals("", locked.body) // Review Focus 2：423 空体
  }

  @Test
  fun noActiveMembershipIs403() {
    val resp = postLogin(loginBody("charlie", "dev123456"))
    assertEquals(HttpStatusCode.Forbidden, resp.status)
    assertEquals("FORBIDDEN", Json.decodeFromString<ErrorResponse>(resp.body).code)
  }

  @Test
  fun inactiveTenantMembershipIs403() {
    assertEquals(HttpStatusCode.Forbidden, postLogin(loginBody("dave", "dev123456")).status)
  }

  @Test
  fun unknownClientIdIs400() {
    assertEquals(
        HttpStatusCode.BadRequest,
        postLogin(loginBody("alice", "dev123456", "no-such-app")).status,
    )
  }

  @Test
  @Fn(["M01.F04.I01"])
  fun emptySubscriptionDoesNotFilterAvailableTenants() {
    // Review Focus 4：second-app 零订阅行 → 不过滤，T1+T2 全集
    val dto = decodeLogin(postLogin(loginBody("alice", "dev123456", ScratchDb.SECOND_CLIENT_ID)))
    assertEquals(
        setOf(ScratchDb.TENANT1, ScratchDb.TENANT2),
        dto.availableTenants.map { it.tenantId }.toSet(),
    )
    assertEquals(ScratchDb.TENANT1, dto.currentTenantId)
  }

  @Test
  fun outOfDomainUserStatusReportsDisabled() = runBlocking {
    // 家族镜像语义：sys_user.status 域外值兜底 disabled（fastapi assemble.py:40），
    // Phase 3 曾兜 active（终审 deferred minor，Phase 4 搭车修）。列形照 ScratchDb seed。
    val oddUserId = Uuid.random()
    suspendTransaction(db) {
      SysUserTable.insert {
        it[id] = oddUserId
        it[username] = "oddstatus"
        it[password] = "plain:dev123456"
        it[email] = "odd@example.com"
        it[status] = 9 // 域外：0=disabled 1=active 2=invited 之外
        it[failedAttempts] = 0
        it[createdAt] = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
        it[updatedAt] = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
      }
      TenantMemberTable.insert {
        it[id] = Uuid.random()
        it[tenantId] = ScratchDb.TENANT1
        it[userId] = oddUserId
        it[isOwner] = false
        it[status] = 1
        it[createdAt] = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
        it[updatedAt] = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
      }
    }
    val dto = decodeLogin(postLogin(loginBody("oddstatus", "dev123456")))
    assertEquals("disabled", dto.user.status.toString()) // 现实现兜 active → RED
  }
}
