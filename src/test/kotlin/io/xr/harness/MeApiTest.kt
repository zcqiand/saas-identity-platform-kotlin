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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
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
import saas.identity.entity.SysMenuTable
import saas.identity.entity.SysRoleMenuTable
import saas.identity.entity.SysRoleTable
import saas.identity.entity.TenantMemberRoleTable
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

  /** switch 族（M01.F03.I02；fastapi me_impl.py switch_tenant 镜像）。 */
  private suspend fun switchTenant(token: String, tenantId: String): Reply = withApp { client ->
    val r =
        client.post("/api/v1/me/tenants/$tenantId/switch") {
          header("Authorization", "Bearer $token")
        }
    Reply(r.status, r.bodyAsText())
  }

  /** 切换重发对：新 access 带 target claim、refresh 纯构造前缀、expiresAt/tenantId 回显。 */
  @Test
  @Fn(["M01.F03.I02"])
  fun switchTenantReissuesWithTargetClaim() {
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { switchTenant(token, ScratchDb.TENANT2.toString()) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    val newAccess = obj["accessToken"]!!.jsonPrimitive.content
    assertEquals(
        ScratchDb.TENANT2.toString(),
        jwt.decode(newAccess).get("tenant_id"),
    )
    assertTrue(obj["refreshToken"]!!.jsonPrimitive.content.startsWith("saas-rt-"))
    assertTrue(obj["expiresAt"]!!.jsonPrimitive.content.isNotEmpty())
    assertEquals(ScratchDb.TENANT2.toString(), obj["tenantId"]!!.jsonPrimitive.content)
  }

  /** 非 member 租户 → 404 NOT_FOUND 不 403（me_impl.py 语义锚点：不泄露租户存在性）。 */
  @Test
  @Fn(["M01.F03.I02"])
  fun switchToNonMemberTenantIs404Not403() {
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { switchTenant(token, ScratchDb.TENANT3.toString()) }
    assertEquals(HttpStatusCode.NotFound, resp.status)
    assertTrue(resp.body.contains("not an active member"))
  }

  /** 门槛 = 非 disabled：invited(2)/suspended(3) 可切，disabled(0) 404（S5 口径）。 */
  @Test
  @Fn(["M01.F03.I02"])
  fun invitedAndSuspendedMembersMaySwitchDisabledMayNot() {
    val bob = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    val carol = Uuid.parse("cccccccc-cccc-cccc-cccc-cccccccccccc")
    runBlocking {
      insertMember(Uuid.random(), ScratchDb.TENANT2, bob, 2.toShort())
      insertMember(Uuid.random(), ScratchDb.TENANT2, carol, 3.toShort())
      insertMember(Uuid.random(), ScratchDb.TENANT2, ScratchDb.DAVE, 0.toShort())
    }
    val bobReply = runBlocking {
      switchTenant(jwt.issueAccessToken(bob, ScratchDb.TENANT1), ScratchDb.TENANT2.toString())
    }
    assertEquals(HttpStatusCode.OK, bobReply.status)
    val carolReply = runBlocking {
      switchTenant(jwt.issueAccessToken(carol, ScratchDb.TENANT1), ScratchDb.TENANT2.toString())
    }
    assertEquals(HttpStatusCode.OK, carolReply.status)
    val daveReply = runBlocking {
      switchTenant(
          jwt.issueAccessToken(ScratchDb.DAVE, ScratchDb.TENANT3),
          ScratchDb.TENANT2.toString(),
      )
    }
    assertEquals(HttpStatusCode.NotFound, daveReply.status)
  }

  /** 未知租户 → 404（message 含 tenant）。 */
  @Test
  @Fn(["M01.F03.I02"])
  fun switchUnknownTenantIs404() {
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { switchTenant(token, Uuid.random().toString()) }
    assertEquals(HttpStatusCode.NotFound, resp.status)
    assertTrue(resp.body.contains("tenant"))
  }

  /** 非 UUID 路径参 → 400 BAD_REQUEST。 */
  @Test
  @Fn(["M01.F03.I02"])
  fun switchBadUuidIs400() {
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { switchTenant(token, "not-a-uuid") }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
  }

  /** 旧 token 不失效：switch 后旧 access 仍可 GET /me 200。 */
  @Test
  fun switchTenantOldTokenStillValid() {
    val token = runBlocking { loginToken("alice") }
    val switch = runBlocking { switchTenant(token, ScratchDb.TENANT2.toString()) }
    assertEquals(HttpStatusCode.OK, switch.status)
    val after = withApp { client ->
      val r = client.get("/api/v1/me") { header("Authorization", "Bearer $token") }
      Reply(r.status, r.bodyAsText())
    }
    assertEquals(HttpStatusCode.OK, after.status)
  }

  /** menus 族（M04.F04.I08；fastapi me_impl.py get_my_menus 镜像）。 */
  private suspend fun getMenus(token: String?): Reply = withApp { client ->
    val builder =
        if (token == null) client.get("/api/v1/me/menus")
        else {
          client.get("/api/v1/me/menus") { header("Authorization", "Bearer $token") }
        }
    Reply(builder.status, builder.bodyAsText())
  }

  /**
   * 四跳 join：member→role→menu→rows，按 clientId 分组组树；root 层 parentId 零值 sentinel → wire null（5.112①
   * 契约修正）。
   */
  @Test
  @Fn(["M04.F04.I08"])
  fun menusGroupsByClientAndBuildsTree() {
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { getMenus(token) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    val console = obj["saas-console"]!!.jsonArray
    assertEquals(1, console.size)
    val root = console[0].jsonObject
    assertEquals(ScratchDb.MENU_ROOT.toString(), root["id"]!!.jsonPrimitive.content)
    assertEquals(kotlinx.serialization.json.JsonNull, root["parentId"])
    assertEquals("directory", root["type"]!!.jsonPrimitive.content)
    assertEquals(
        listOf(ScratchDb.MENU_CHILD.toString()),
        root["children"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
    val second = obj["second-app"]!!.jsonArray
    assertEquals(
        listOf(ScratchDb.MENU_OTHER_CLIENT.toString()),
        second.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
  }

  /** root 层按 sortOrder 升序；children 保持装载序不重排（me_impl.py:307-311 镜像）。 */
  @Test
  @Fn(["M04.F04.I08"])
  fun menusRootsSortedBySortOrderChildrenKeepInsertion() {
    val extraRoot = Uuid.parse("17171717-1717-1717-1717-171717171717")
    val lateChild = Uuid.parse("18181818-1818-1818-1818-181818181818")
    runBlocking {
      suspendTransaction(db) {
        // 新 root：sortOrder=0（< MENU_ROOT 的 1）→ 排最前
        SysMenuTable.insert {
          it[id] = extraRoot
          it[clientId] = ScratchDb.CLIENT_ID
          it[parentId] = ScratchDb.ZERO_UUID
          it[title] = "前置菜单"
          it[type] = 1
          it[path] = "/first"
          it[component] = null
          it[perms] = null
          it[icon] = null
          it[sortOrder] = 0
          it[status] = 1
          it[createdAt] = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
        }
        // MENU_ROOT 第二个 child：sortOrder=0 但装载在后 → children[0] 仍 MENU_CHILD
        SysMenuTable.insert {
          it[id] = lateChild
          it[clientId] = ScratchDb.CLIENT_ID
          it[parentId] = ScratchDb.MENU_ROOT
          it[title] = "后挂子页"
          it[type] = 2
          it[path] = "/late"
          it[component] = null
          it[perms] = null
          it[icon] = null
          it[sortOrder] = 0
          it[status] = 1
          it[createdAt] = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
        }
        SysRoleMenuTable.insert {
          it[roleId] = ScratchDb.ROLE1
          it[menuId] = extraRoot
        }
        SysRoleMenuTable.insert {
          it[roleId] = ScratchDb.ROLE1
          it[menuId] = lateChild
        }
      }
    }
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { getMenus(token) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val console = Json.parseToJsonElement(resp.body).jsonObject["saas-console"]!!.jsonArray
    assertEquals(
        listOf(extraRoot.toString(), ScratchDb.MENU_ROOT.toString()),
        console.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
    val root = console.first {
      it.jsonObject["id"]!!.jsonPrimitive.content == ScratchDb.MENU_ROOT.toString()
    }
    assertEquals(
        listOf(ScratchDb.MENU_CHILD.toString(), lateChild.toString()),
        root.jsonObject["children"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
  }

  /** 根 sentinel（DB 零值 parent_id）→ wire parentId=null（契约 5.112① 修正：required+nullable，四后端实测口径）。 */
  @Test
  @Fn(["M04.F04.I08"])
  fun menusRootSentinelSerializesNullParentId() {
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { getMenus(token) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val console = Json.parseToJsonElement(resp.body).jsonObject["saas-console"]!!.jsonArray
    val root = console.first {
      it.jsonObject["id"]!!.jsonPrimitive.content == ScratchDb.MENU_ROOT.toString()
    }
    assertEquals(
        kotlinx.serialization.json.JsonNull,
        root.jsonObject["parentId"],
    )
    // 非 sentinel parent（子页）不受影响：照常回显
    val child =
        console
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == ScratchDb.MENU_ROOT.toString() }
            .jsonObject["children"]!!
            .jsonArray
            .first()
    assertEquals(
        ScratchDb.MENU_ROOT.toString(),
        child.jsonObject["parentId"]!!.jsonPrimitive.content,
    )
  }

  /** 孤儿：授权但 parent 不在授权集合 → 仍当 root，parentId 原样回显（5.112① 后仅零值 sentinel 归 null，孤儿不受影响）。 */
  @Test
  @Fn(["M04.F04.I08"])
  fun menusOrphanParentStillRootWithEchoedParentId() {
    runBlocking {
      suspendTransaction(db) {
        SysRoleMenuTable.insert {
          it[roleId] = ScratchDb.ROLE1
          it[menuId] = ScratchDb.MENU_ORPHAN_CHILD
        }
      }
    }
    val token = runBlocking { loginToken("alice") }
    val resp = runBlocking { getMenus(token) }
    assertEquals(HttpStatusCode.OK, resp.status)
    val console = Json.parseToJsonElement(resp.body).jsonObject["saas-console"]!!.jsonArray
    val orphan = console.first {
      it.jsonObject["id"]!!.jsonPrimitive.content == ScratchDb.MENU_ORPHAN_CHILD.toString()
    }
    assertEquals(
        ScratchDb.ORPHAN_PARENT.toString(),
        orphan.jsonObject["parentId"]!!.jsonPrimitive.content,
    )
    assertEquals("menu", orphan.jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(0, orphan.jsonObject["children"]!!.jsonArray.size)
  }

  /** 空 chain（无 member / 无 role / 无 menu 授权）→ {}；无头 401。 */
  @Test
  @Fn(["M04.F04.I08"])
  fun menusEmptyChainStagesReturnEmptyMap() {
    val resp = runBlocking { getMenus(jwt.issueAccessToken(ScratchDb.CHARLIE, ScratchDb.TENANT1)) }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertEquals(0, Json.parseToJsonElement(resp.body).jsonObject.size)
    assertEquals(HttpStatusCode.Unauthorized, runBlocking { getMenus(null) }.status)
  }

  /** 未知用户（无 member 行）→ {} 不 401（me_impl.py:83-84 恒返回镜像）。 */
  @Test
  fun menusUnknownUserReturnsEmptyMap() {
    val resp = runBlocking { getMenus(jwt.issueAccessToken(Uuid.random(), ScratchDb.TENANT1)) }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertEquals(0, Json.parseToJsonElement(resp.body).jsonObject.size)
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

  /** 终审 I1 钉：坏 Bearer（签名不符/过期）→ 401 非 500（fastapi security.py PyJWTError 收敛镜像）。 */
  @Test
  fun meTamperedBearerTokenIs401Not500() {
    val tampered = runBlocking { loginToken("alice") } + "x"
    val resp = withApp { client ->
      Reply(
          client.get("/api/v1/me") { header("Authorization", "Bearer $tampered") }.status,
          "",
      )
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  /** 终审 I2 钉（Review Focus #5）：whoami 未知用户（无 member 行）→ 200 空 memberships 不崩（firstOrNull 守卫）。 */
  @Test
  fun whoamiUnknownUserReturns200EmptyMemberships() {
    val stranger = Uuid.random()
    val token = jwt.issueAccessToken(stranger, ScratchDb.TENANT1)
    val resp = withApp { client ->
      val r = client.get("/api/v1/me") { header("Authorization", "Bearer $token") }
      Reply(r.status, r.bodyAsText())
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.body).jsonObject
    assertEquals(0, obj["memberships"]!!.jsonArray.size)
    // claim 优先：空 memberships 时 currentTenantId 仍取 claim（真 null 分支=无 claim，HTTP 不可复现，缺口在案）
    assertEquals(ScratchDb.TENANT1.toString(), obj["currentTenantId"]!!.jsonPrimitive.content)
  }

  /** 终审 I2 钉（Review Focus #4）：member 在但无角色 → {}（TenantMemberRoleTable 无行分支）。 */
  @Test
  fun menusMemberWithoutRolesReturnsEmptyMap() {
    val roleless = Uuid.random()
    runBlocking { insertMember(Uuid.random(), ScratchDb.TENANT1, roleless, 1) }
    val resp = runBlocking { getMenus(jwt.issueAccessToken(roleless, ScratchDb.TENANT1)) }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertEquals(0, Json.parseToJsonElement(resp.body).jsonObject.size)
  }

  /** 终审 I2 钉（Review Focus #4）：角色在但无菜单授权 → {}（SysRoleMenuTable 无行分支）。 */
  @Test
  fun menusRoleWithoutMenuGrantsReturnsEmptyMap() {
    val granted = Uuid.random()
    val bareRole = Uuid.random()
    runBlocking {
      insertMember(Uuid.random(), ScratchDb.TENANT1, granted, 1)
      suspendTransaction(db) {
        val grantedMemberId =
            saas.identity.entity.TenantMemberTable.selectAll()
                .where {
                  (saas.identity.entity.TenantMemberTable.userId eq granted) and
                      (saas.identity.entity.TenantMemberTable.tenantId eq ScratchDb.TENANT1)
                }
                .toList()
                .first()[saas.identity.entity.TenantMemberTable.id]
        SysRoleTable.insert {
          it[id] = bareRole
          it[tenantId] = ScratchDb.TENANT1
          it[clientId] = ScratchDb.CLIENT_ID
          it[roleCode] = "bare"
          it[roleName] = "无授权角色"
          it[description] = null
          it[isPreset] = false
          it[status] = 1
          it[createdAt] = java.time.OffsetDateTime.parse("2026-06-01T00:00:00+00:00")
          it[updatedAt] = java.time.OffsetDateTime.parse("2026-06-01T00:00:00+00:00")
        }
        TenantMemberRoleTable.insert {
          it[memberId] = grantedMemberId
          it[roleId] = bareRole
        }
      }
    }
    val resp = runBlocking { getMenus(jwt.issueAccessToken(granted, ScratchDb.TENANT1)) }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertEquals(0, Json.parseToJsonElement(resp.body).jsonObject.size)
  }
}
