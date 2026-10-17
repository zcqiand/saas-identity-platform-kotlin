package io.xr.harness

import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import saas.identity.entity.OauthAccessTokenTable
import saas.identity.entity.OauthClientTable
import saas.identity.entity.OauthCodeTable
import saas.identity.entity.OauthRefreshTokenTable
import saas.identity.entity.SysMenuTable
import saas.identity.entity.SysRoleMenuTable
import saas.identity.entity.SysRoleTable
import saas.identity.entity.SysUserTable
import saas.identity.entity.TenantApplicationTable
import saas.identity.entity.TenantMemberRoleTable
import saas.identity.entity.TenantMemberTable
import saas.identity.entity.TenantTable

/**
 * session 级 scratch 库（fastapi conftest 同构）：DROP(WITH FORCE)→CREATE→createTables→ 种子镜像；case 间
 * truncateAndSeed；收工 drop。DATABASE_URL 提供服务器+凭据，库名换 scratch。
 */
object ScratchDb {

  const val SCRATCH_DB = "saas_kotlin_scratch"

  // 固定夹具 UUID（fastapi _seed 语义对齐；T1/T2=租户、T3=停用租户、A/C/D=用户）
  val TENANT1 = Uuid.parse("11111111-1111-1111-1111-111111111111")
  val TENANT2 = Uuid.parse("22222222-2222-2222-2222-222222222222")
  val TENANT3 = Uuid.parse("33333333-3333-3333-3333-333333333333")
  val ALICE = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  val CHARLIE = Uuid.parse("cccccccc-cccc-cccc-cccc-cccccccccccc")
  val DAVE = Uuid.parse("dddddddd-dddd-dddd-dddd-dddddddddddd")
  val MEMBER1 = Uuid.parse("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
  val MEMBER2 = Uuid.parse("f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f0")
  val MEMBER3 = Uuid.parse("f1f1f1f1-f1f1-f1f1-f1f1-f1f1f1f1f1f1")
  val ROLE1 = Uuid.parse("99999999-9999-9999-9999-999999999999")
  const val CLIENT_ID = "saas-console"
  const val SECOND_CLIENT_ID = "second-app"

  // 菜单夹具（me/menus 用）：saas-console 两节点（root→child）+ 孤儿 + second-app 单节点
  val MENU_ROOT = Uuid.parse("12121212-1212-1212-1212-121212121212")
  val MENU_CHILD = Uuid.parse("13131313-1313-1313-1313-131313131313")
  val MENU_ORPHAN_CHILD = Uuid.parse("14141414-1414-1414-1414-141414141414")
  val MENU_OTHER_CLIENT = Uuid.parse("15151515-1515-1515-1515-151515151515")
  // 真·孤儿父（不在授权集合、非零值）：固定值保证断言可回显（brief 的 Uuid.random() 改固定 sentinel——确定性）
  val ORPHAN_PARENT = Uuid.parse("16161616-1616-1616-1616-161616161616")
  val ZERO_UUID = Uuid.parse("00000000-0000-0000-0000-000000000000")

  private val rawUrl: String =
      System.getenv("DATABASE_URL")
          ?: error(
              "DATABASE_URL 未设（hard-rules §1 禁兜底）。gate 入表注入 saas_test URL；" +
                  "手工跑先 export DATABASE_URL='postgresql://…@127.0.0.1:54321/saas_test'"
          )

  /**
   * 家族形 postgresql:// 单串、库名=scratch（喂 AppConfig.databaseUrl；harnessModule 内做 r2dbc 映射）。 禁直喂
   * rawUrl——那会把被测 app 指进 saas_test 本库（2026-10-16 污染事故）。
   */
  val scratchPgUrl: String = rawUrl.replace(Regex("/[^/]+$"), "/$SCRATCH_DB")

  val r2dbcUrl: String =
      rawUrl
          .replaceFirst("postgresql://", "r2dbc:postgresql://")
          .replace(Regex("/[^/]+$"), "/$SCRATCH_DB")

  private fun adminConnection(): Connection {
    val uri = URI(rawUrl)
    val info = uri.userInfo ?: error("DATABASE_URL 缺用户:密码（family 契约 postgres:postgres）")
    val port = if (uri.port == -1) 5432 else uri.port
    return DriverManager.getConnection(
        "jdbc:postgresql://${uri.host}:$port/postgres",
        info.substringBefore(":"),
        info.substringAfter(":"),
    )
  }

  private fun sql(conn: Connection, statement: String) =
      conn.createStatement().use { it.execute(statement) }

  fun prepare() {
    adminConnection().use { admin ->
      admin.autoCommit = true
      sql(admin, "DROP DATABASE IF EXISTS \"$SCRATCH_DB\" WITH (FORCE)")
      sql(admin, "CREATE DATABASE \"$SCRATCH_DB\"")
    }
  }

  fun drop() {
    adminConnection().use { admin ->
      admin.autoCommit = true
      sql(admin, "DROP DATABASE IF EXISTS \"$SCRATCH_DB\" WITH (FORCE)")
    }
  }

  fun r2dbcDb(): R2dbcDatabase = R2dbcDatabase.connect(r2dbcUrl)

  private val allTables =
      arrayOf(
          SysUserTable,
          TenantTable,
          TenantMemberTable,
          TenantMemberRoleTable,
          TenantApplicationTable,
          OauthClientTable,
          OauthAccessTokenTable,
          OauthRefreshTokenTable,
          OauthCodeTable,
          SysRoleTable,
          SysMenuTable,
          SysRoleMenuTable,
      )

  suspend fun truncateAndSeed(db: R2dbcDatabase) {
    suspendTransaction(db) {
      // SchemaUtils 先建（幂等 CREATE IF NOT EXISTS）：scratch 库新 DROP 出来时表尚不存在
      SchemaUtils.create(*allTables)
      val names = allTables.joinToString(", ") { "\"${it.tableName}\"" }
      exec("TRUNCATE $names RESTART IDENTITY CASCADE")

      val t0 = java.time.OffsetDateTime.parse("2026-01-01T00:00:00+00:00")
      seedTenants(t0)
      seedUsers(t0)
      seedMembers(t0)
      seedClients(t0)
      seedMenus(t0)
    }
  }

  private suspend fun seedTenants(t0: java.time.OffsetDateTime) {
    TenantTable.insert {
      it[id] = TENANT1
      it[tenantKey] = "t1"
      it[name] = "租户一"
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    TenantTable.insert {
      it[id] = TENANT2
      it[tenantKey] = "t2"
      it[name] = "租户二"
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    TenantTable.insert {
      it[id] = TENANT3
      it[tenantKey] = "t3"
      it[name] = "停用租户"
      it[status] = 0
      it[createdAt] = t0
      it[updatedAt] = t0
    }
  }

  private suspend fun seedUsers(t0: java.time.OffsetDateTime) {
    SysUserTable.insert {
      it[id] = ALICE
      it[username] = "alice"
      it[password] = "plain:dev123456"
      it[status] = 1
      it[failedAttempts] = 0
      it[email] = "alice@example.com"
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    SysUserTable.insert {
      it[id] = CHARLIE
      it[username] = "charlie"
      it[password] = "plain:dev123456"
      it[status] = 1
      it[failedAttempts] = 0
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    SysUserTable.insert {
      it[id] = DAVE
      it[username] = "dave"
      it[password] = "plain:dev123456"
      it[status] = 1
      it[failedAttempts] = 0
      it[createdAt] = t0
      it[updatedAt] = t0
    }
  }

  private suspend fun seedMembers(t0: java.time.OffsetDateTime) {
    TenantMemberTable.insert {
      it[id] = MEMBER1
      it[tenantId] = TENANT1
      it[userId] = ALICE
      it[isOwner] = true
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    TenantMemberTable.insert {
      it[id] = MEMBER2
      it[tenantId] = TENANT2
      it[userId] = ALICE
      it[isOwner] = false
      it[status] = 1
      it[createdAt] = t0.plusSeconds(1)
      it[updatedAt] = t0
    }
    TenantMemberTable.insert {
      it[id] = MEMBER3
      it[tenantId] = TENANT3
      it[userId] = DAVE
      it[isOwner] = false
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    TenantMemberRoleTable.insert {
      it[memberId] = MEMBER1
      it[roleId] = ROLE1
    }
  }

  private suspend fun seedMenus(t0: java.time.OffsetDateTime) {
    SysRoleTable.insert {
      it[id] = ROLE1
      it[tenantId] = TENANT1
      it[clientId] = CLIENT_ID
      it[roleCode] = "admin"
      it[roleName] = "管理员"
      it[description] = null
      it[isPreset] = true
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    SysMenuTable.insert { // root：parent=零值 sentinel
      it[id] = MENU_ROOT
      it[clientId] = CLIENT_ID
      it[parentId] = ZERO_UUID
      it[title] = "工作台"
      it[type] = 1
      it[path] = "/dashboard"
      it[component] = null
      it[perms] = null
      it[icon] = null
      it[sortOrder] = 1
      it[status] = 1
      it[createdAt] = t0
    }
    SysMenuTable.insert { // child：parent=MENU_ROOT（在授权集合）
      it[id] = MENU_CHILD
      it[clientId] = CLIENT_ID
      it[parentId] = MENU_ROOT
      it[title] = "数据看板"
      it[type] = 2
      it[path] = "/dashboard/data"
      it[component] = null
      it[perms] = null
      it[icon] = null
      it[sortOrder] = 2
      it[status] = 1
      it[createdAt] = t0
    }
    SysMenuTable.insert { // 真·孤儿：授权后 parent 不在授权集合 → 仍当 root，parentId 原样回显
      it[id] = MENU_ORPHAN_CHILD
      it[clientId] = CLIENT_ID
      it[parentId] = ORPHAN_PARENT
      it[title] = "孤儿页"
      it[type] = 2
      it[path] = "/orphan"
      it[component] = null
      it[perms] = null
      it[icon] = null
      it[sortOrder] = 0
      it[status] = 1
      it[createdAt] = t0
    }
    SysMenuTable.insert { // 另一 client 的菜单（分组用）
      it[id] = MENU_OTHER_CLIENT
      it[clientId] = SECOND_CLIENT_ID
      it[parentId] = ZERO_UUID
      it[title] = "他端菜单"
      it[type] = 1
      it[path] = "/other"
      it[component] = null
      it[perms] = null
      it[icon] = null
      it[sortOrder] = 1
      it[status] = 1
      it[createdAt] = t0
    }
    SysRoleMenuTable.insert {
      it[roleId] = ROLE1
      it[menuId] = MENU_ROOT
    }
    SysRoleMenuTable.insert {
      it[roleId] = ROLE1
      it[menuId] = MENU_CHILD
    }
    SysRoleMenuTable.insert {
      it[roleId] = ROLE1
      it[menuId] = MENU_OTHER_CLIENT
    }
    // MENU_ORPHAN_CHILD 默认不授权（孤儿用例测试内直插授权行，避免污染其他用例默认形状）
  }

  private suspend fun seedClients(t0: java.time.OffsetDateTime) {
    OauthClientTable.insert {
      it[id] = Uuid.parse("88888888-8888-8888-8888-888888888888")
      it[clientId] = CLIENT_ID
      it[clientSecret] = "plain:console-secret"
      it[clientName] = "saas console"
      it[grantTypes] = "authorization_code,refresh_token,password"
      it[redirectUris] = "http://localhost:5101/callback"
      it[scopes] = null
      it[accessTokenValidity] = 3600
      it[refreshTokenValidity] = 2592000
      it[autoApprove] = false
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    OauthClientTable.insert {
      it[id] = Uuid.parse("77777777-7777-7777-7777-777777777777")
      it[clientId] = SECOND_CLIENT_ID
      it[clientSecret] = "plain:second-secret"
      it[clientName] = "second app"
      it[grantTypes] = "authorization_code,refresh_token,password"
      it[redirectUris] = "http://localhost:5102/callback"
      it[scopes] = null
      it[accessTokenValidity] = 3600
      it[refreshTokenValidity] = 2592000
      it[autoApprove] = false
      it[status] = 1
      it[createdAt] = t0
      it[updatedAt] = t0
    }
    // saas-console 只订阅 T1；second-app 零订阅行（Review Focus 4：空集不过滤）
    TenantApplicationTable.insert {
      it[id] = Uuid.parse("66666666-6666-6666-6666-666666666666")
      it[tenantId] = TENANT1
      it[clientId] = CLIENT_ID
      it[status] = 1
      it[expireTime] = null
      it[createdAt] = t0
    }
  }
}
