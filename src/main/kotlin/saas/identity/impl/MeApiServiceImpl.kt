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
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import saas.identity.entity.SysUserTable
import saas.identity.entity.TenantMemberRoleTable
import saas.identity.entity.TenantMemberTable
import saas.identity.entity.TenantTable
import saas.identity.shared.api.MeApiDelegate
import saas.identity.shared.models.CurrentUser
import saas.identity.shared.models.SwitchTenantResponse
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
  ): Map<String, List<saas.identity.shared.models.EffectiveMenuNode>> = TODO("Task 5")

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

/** claims 取 subject 的共享收口（whoami/switch/menus 三处同语义）。 */
internal fun Claims.subjectUuid(): Uuid? = uuidOrNull(subject)
