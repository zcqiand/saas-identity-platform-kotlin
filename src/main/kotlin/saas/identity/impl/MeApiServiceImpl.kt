package saas.identity.impl

import io.jsonwebtoken.Claims
import io.ktor.server.routing.RoutingCall
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.any
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import saas.identity.entity.SysMenuTable
import saas.identity.entity.SysRoleMenuTable
import saas.identity.entity.SysUserTable
import saas.identity.entity.TenantMemberRoleTable
import saas.identity.entity.TenantMemberTable
import saas.identity.entity.TenantTable
import saas.identity.shared.api.MeApiDelegate
import saas.identity.shared.models.CurrentUser
import saas.identity.shared.models.EffectiveMenuNode
import saas.identity.shared.models.SwitchTenantResponse
import saas.identity.shared.models.SysMenuType
import saas.identity.shared.models.TenantMembership

/** me 族（fastapi me_impl.py 镜像）。本切片全 4 op；member 查询恒 created_at ASC + id ASC（防首个漂移）。 */
class MeApiServiceImpl(private val db: R2dbcDatabase, private val jwt: JwtIssuer) : MeApiDelegate {

  override suspend fun meWhoami(call: RoutingCall): CurrentUser {
    val claims = requireBearer(call, jwt)
    val userId =
        uuidOrNull(claims.subject)
            ?: throw InvalidCredentialsError("Bearer sub required for whoami")
    val email =
        suspendTransaction(db) {
              SysUserTable.selectAll().where { SysUserTable.id eq userId }.singleOrNull()
            }
            ?.get(SysUserTable.email)
    val memberships = membersOf(userId)
    // claim 优先 → 首个 membership；空 = null（me_impl.py:53-59 空守卫镜像，禁 first() 崩——I2 家族口径）
    val currentTenantId = uuidOrNull(claims.get("tenant_id")) ?: memberships.firstOrNull()?.tenantId
    return CurrentUser(
        id = userId,
        memberships = memberships,
        email = email,
        currentTenantId = currentTenantId,
    )
  }

  override suspend fun meListMyTenants(
      clientId: String?,
      call: RoutingCall,
  ): List<TenantMembership> {
    val claims = requireBearer(call, jwt)
    // 无 sub → 空列表（me_impl.py:170 恒返回，不 401——与 whoami 的参考不对称原样保留）
    val userId = uuidOrNull(claims.subject) ?: return emptyList()
    return membersOf(userId)
  }

  override suspend fun meGetMyMenus(
      clientId: String?,
      call: RoutingCall,
  ): Map<String, List<EffectiveMenuNode>> {
    val claims = requireBearer(call, jwt)
    // 无 sub → 空 Map（me_impl.py:83-84 镜像，不 401）
    val userId = uuidOrNull(claims.subject) ?: return emptyMap()
    return suspendTransaction(db) {
      val memberIds =
          TenantMemberTable.selectAll()
              .where { TenantMemberTable.userId eq userId }
              .toList()
              .map { it[TenantMemberTable.id] }
      if (memberIds.isEmpty()) return@suspendTransaction emptyMap()
      val roleIds =
          TenantMemberRoleTable.selectAll()
              .where { TenantMemberRoleTable.memberId inList memberIds }
              .toList()
              .map { it[TenantMemberRoleTable.roleId] }
      if (roleIds.isEmpty()) return@suspendTransaction emptyMap()
      val menuIds =
          SysRoleMenuTable.selectAll()
              .where { SysRoleMenuTable.roleId inList roleIds }
              .toList()
              .map { it[SysRoleMenuTable.menuId] }
      if (menuIds.isEmpty()) return@suspendTransaction emptyMap()
      // clientId query 收但恒全量分组（me_impl.py:117-119 镜像）
      SysMenuTable.selectAll()
          .where { SysMenuTable.id inList menuIds }
          .toList()
          .groupBy { it[SysMenuTable.clientId] }
          .mapValues { (_, rows) -> buildMenuTree(rows) }
    }
  }

  override suspend fun meSwitchTenant(
      tenantId: String,
      clientId: String?,
      call: RoutingCall,
  ): SwitchTenantResponse {
    val claims = requireBearer(call, jwt)
    val userId =
        uuidOrNull(claims.subject)
            ?: throw InvalidCredentialsError("Bearer sub required for tenant switch")
    val target = uuidOrNull(tenantId) ?: throw BadRequestError("invalid tenantId: $tenantId")
    val tenantExists =
        suspendTransaction(db) {
          TenantTable.selectAll().where { TenantTable.id eq target }.firstOrNull() != null
        }
    if (!tenantExists) throw NotFoundError("tenant $target")
    // 成员资格门槛 = 非 disabled（status≠0；invited/suspended 可切；S5 口径）
    val isMember =
        suspendTransaction(db) {
          TenantMemberTable.selectAll()
              .where { TenantMemberTable.userId eq userId }
              .any {
                it[TenantMemberTable.tenantId] == target &&
                    it[TenantMemberTable.status] != 0.toShort()
              }
        }
    if (!isMember) {
      throw NotFoundError("user $userId is not an active member of tenant $target")
    }
    // refresh 纯构造不落库（fastapi me_impl.py:206-207 镜像）
    val refresh = "saas-rt-$userId-${System.currentTimeMillis()}-${Uuid.random()}"
    return SwitchTenantResponse(
        accessToken = jwt.issueAccessToken(userId, target),
        refreshToken = refresh,
        expiresAt = iso(OffsetDateTime.now().plusSeconds(jwt.ttlSeconds)),
        tenantId = target,
    )
  }

  /** membership 全量行（无 status 过滤），固定序。 */
  private suspend fun membersOf(userId: Uuid): List<TenantMembership> =
      suspendTransaction(db) {
            TenantMemberTable.selectAll()
                .where { TenantMemberTable.userId eq userId }
                .orderBy(
                    TenantMemberTable.createdAt to SortOrder.ASC,
                    TenantMemberTable.id to SortOrder.ASC,
                )
                .toList()
          }
          .map { toMembership(it) }

  /** roleIds join 原样，不按 sys_role.tenant_id 过滤（家族 2026-09-12 教训）。 */
  private suspend fun toMembership(row: ResultRow): TenantMembership =
      TenantMembership(
          id = row[TenantMemberTable.id],
          userId = row[TenantMemberTable.userId],
          tenantId = row[TenantMemberTable.tenantId],
          roleIds = roleIdsOf(row[TenantMemberTable.id]),
          status = memberStatusFromDb(row[TenantMemberTable.status]),
          joinedAt = iso(row[TenantMemberTable.createdAt]),
      )

  private suspend fun roleIdsOf(memberId: Uuid): List<String> =
      suspendTransaction(db) {
            TenantMemberRoleTable.selectAll()
                .where { TenantMemberRoleTable.memberId eq memberId }
                .toList()
          }
          .map { it[TenantMemberRoleTable.roleId].toString() }
}

/**
 * 组树：parent 在授权集合内 → 挂 children，否则（含孤儿/零值 sentinel/自指）当 root。生成 EffectiveMenuNode.children 是不可变
 * List——plan 的 parent.children.add 编不过，改局部 mutable map + copy 递归填充。children
 * 保持装载序不重排；只排根层（me_impl.py:307-311 镜像）。
 */
private fun buildMenuTree(rows: List<ResultRow>): List<EffectiveMenuNode> {
  val nodes = rows.associate { row ->
    row[SysMenuTable.id] to
        EffectiveMenuNode(
            id = row[SysMenuTable.id],
            clientId = row[SysMenuTable.clientId],
            // 非空契约 Ruling：参考吐 null 的场景此处零值/孤儿 parent 原样回显（tracker 5.112 候选）
            parentId = row[SysMenuTable.parentId],
            title = row[SysMenuTable.title],
            type = sysMenuTypeFromDb(row[SysMenuTable.type]),
            sortOrder = row[SysMenuTable.sortOrder],
            children = emptyList(),
            path = row[SysMenuTable.path],
            component = row[SysMenuTable.component],
            perms = row[SysMenuTable.perms],
            icon = row[SysMenuTable.icon],
        )
  }
  val childrenOf = HashMap<Uuid, MutableList<EffectiveMenuNode>>()
  val roots = mutableListOf<EffectiveMenuNode>()
  for (row in rows) {
    val node = nodes.getValue(row[SysMenuTable.id])
    val parent = nodes[row[SysMenuTable.parentId]]
    if (parent == null || parent.id == node.id) roots.add(node)
    else childrenOf.getOrPut(parent.id) { mutableListOf() }.add(node)
  }

  fun fill(node: EffectiveMenuNode): EffectiveMenuNode =
      node.copy(children = (childrenOf[node.id] ?: emptyList()).map { fill(it) })

  return roots.sortedBy { it.sortOrder }.map { fill(it) }
}

/** 1=directory / 3=button / 2 与未知=menu（menus_impl.py:55-60 镜像；Short 列型按家族 when(toInt()) 口径）。 */
internal fun sysMenuTypeFromDb(value: Short): SysMenuType =
    when (value.toInt()) {
      MENU_TYPE_DIRECTORY -> SysMenuType.directory
      MENU_TYPE_BUTTON -> SysMenuType.button
      else -> SysMenuType.menu
    }

// sys_menu.type 字典值（fastapi seed 与查询同语义；命名替代裸字面量）
private const val MENU_TYPE_DIRECTORY = 1
private const val MENU_TYPE_BUTTON = 3

/** claims 取 subject 的共享收口（whoami/switch/menus 三处同语义）。 */
internal fun Claims.subjectUuid(): Uuid? = uuidOrNull(subject)
