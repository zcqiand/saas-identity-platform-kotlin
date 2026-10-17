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
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
import saas.identity.entity.TenantMemberTable
import saas.identity.impl.JwtIssuer

/** me 族（M01.F01.I01 whoami + M01.F03.I01 my-tenants；fastapi me_impl.py:38-177 镜像）。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeApiTest {

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

  private suspend fun loginToken(username: String): String = withApp { client ->
    val resp =
        client.post("/api/v1/auth/login") {
          contentType(ContentType.Application.Json)
          setBody("""{"username":"$username","password":"dev123456","clientId":"saas-console"}""")
        }
    assertEquals(HttpStatusCode.OK, resp.status)
    Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
  }

  private suspend fun insertMember(
      memberId: Uuid,
      tenantId: Uuid,
      userId: Uuid,
      memberStatus: Short,
  ) =
      suspendTransaction(db) {
        TenantMemberTable.insert {
          it[TenantMemberTable.id] = memberId
          it[TenantMemberTable.tenantId] = tenantId
          it[TenantMemberTable.userId] = userId
          it[isOwner] = false
          it[TenantMemberTable.status] = memberStatus
          it[createdAt] = java.time.OffsetDateTime.parse("2026-06-01T00:00:00+00:00")
          it[updatedAt] = java.time.OffsetDateTime.parse("2026-06-01T00:00:00+00:00")
        }
      }

  private suspend fun memberCount(userId: Uuid): Int =
      suspendTransaction(db) {
        TenantMemberTable.selectAll().where { TenantMemberTable.userId eq userId }.toList().size
      }

  /** claim tenant_id 优先；memberships 全量（whoami 不过滤订阅/状态，与 login 的 availableTenants 不同）。 */
  @Test
  @Fn(["M01.F01.I01"])
  fun whoamiReturnsClaimTenantAndMemberships() {
    val token = runBlocking { loginToken("alice") }
    val resp = withApp { client ->
      val r = client.get("/api/v1/me") { header("Authorization", "Bearer $token") }
      Reply(r.status, r.bodyAsText())
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals(ScratchDb.ALICE.toString(), obj["id"]!!.jsonPrimitive.content)
    assertEquals("alice@example.com", obj["email"]!!.jsonPrimitive.content)
    assertEquals(ScratchDb.TENANT1.toString(), obj["currentTenantId"]!!.jsonPrimitive.content)
    val memberships = obj["memberships"]!!.jsonArray
    assertEquals(2, memberships.size)
    val t1 = memberships.first {
      it.jsonObject["tenantId"]!!.jsonPrimitive.content == ScratchDb.TENANT1.toString()
    }
    assertTrue(t1.jsonObject["roleIds"]!!.jsonArray.toString().contains(ScratchDb.ROLE1.toString()))
    assertEquals("active", t1.jsonObject["status"]!!.jsonPrimitive.content)
  }

  /** 排序契约恒 created_at ASC + id ASC：T1 先 T2 后。 */
  @Test
  @Fn(["M01.F03.I01"])
  fun myTenantsReturnsAllInFixedOrder() {
    val token = runBlocking { loginToken("alice") }
    val resp = withApp { client ->
      val r = client.get("/api/v1/me/tenants") { header("Authorization", "Bearer $token") }
      Reply(r.status, r.bodyAsText())
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val arr = Json.parseToJsonElement(resp.body).jsonArray
    assertEquals(2, arr.size)
    assertEquals(
        ScratchDb.TENANT1.toString(),
        arr[0].jsonObject["tenantId"]!!.jsonPrimitive.content,
    )
    assertEquals(
        ScratchDb.TENANT2.toString(),
        arr[1].jsonObject["tenantId"]!!.jsonPrimitive.content,
    )
    assertEquals("active", arr[0].jsonObject["status"]!!.jsonPrimitive.content)
    assertTrue(arr[0].jsonObject["joinedAt"]!!.jsonPrimitive.content.isNotEmpty())
  }

  /** 无 status 过滤：disabled membership 也回（me_impl.py:158-177 镜像）。 */
  @Test
  @Fn(["M01.F03.I01"])
  fun myTenantsDisabledMembershipStillListed() {
    runBlocking {
      insertMember(
          Uuid.parse("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1"),
          ScratchDb.TENANT1,
          ScratchDb.DAVE,
          0.toShort(),
      )
    }
    val token = jwt.issueAccessToken(ScratchDb.DAVE, ScratchDb.TENANT3)
    val resp = withApp { client ->
      val r = client.get("/api/v1/me/tenants") { header("Authorization", "Bearer $token") }
      Reply(r.status, r.bodyAsText())
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val arr = Json.parseToJsonElement(resp.body).jsonArray
    assertEquals(2, arr.size)
    val disabled = arr.first {
      it.jsonObject["tenantId"]!!.jsonPrimitive.content == ScratchDb.TENANT1.toString()
    }
    assertEquals("disabled", disabled.jsonObject["status"]!!.jsonPrimitive.content)
  }

  /** 未知用户（无 member 行）→ 空列表不 401（me_impl.py:170 恒返回，与 whoami 不对称）。 */
  @Test
  @Fn(["M01.F03.I01"])
  fun myTenantsUnknownUserReturnsEmptyList() {
    val token = jwt.issueAccessToken(Uuid.random(), ScratchDb.TENANT1)
    val resp = withApp { client ->
      val r = client.get("/api/v1/me/tenants") { header("Authorization", "Bearer $token") }
      Reply(r.status, r.bodyAsText())
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertEquals(0, Json.parseToJsonElement(resp.body).jsonArray.size)
  }

  /** 设施面：无头 / 非 Bearer 前缀 / Bearer 空串三路 401（不挂 ID）。 */
  @Test
  fun meBearerBoundariesAre401() {
    val noHeader = withApp { client -> Reply(client.get("/api/v1/me").status, "") }
    assertEquals(HttpStatusCode.Unauthorized, noHeader.status)
    val basic = withApp { client ->
      Reply(
          client.get("/api/v1/me") { header("Authorization", "Basic abc") }.status,
          "",
      )
    }
    assertEquals(HttpStatusCode.Unauthorized, basic.status)
    val empty = withApp { client ->
      Reply(
          client.get("/api/v1/me/tenants") { header("Authorization", "Bearer ") }.status,
          "",
      )
    }
    assertEquals(HttpStatusCode.Unauthorized, empty.status)
  }
}
