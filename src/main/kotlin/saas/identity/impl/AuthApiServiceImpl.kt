package saas.identity.impl

import io.ktor.server.routing.RoutingCall
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import saas.identity.entity.SysUserTable
import saas.identity.entity.TenantApplicationTable
import saas.identity.entity.TenantMemberRoleTable
import saas.identity.entity.TenantMemberTable
import saas.identity.entity.TenantTable
import saas.identity.shared.api.AuthApiDelegate
import saas.identity.shared.models.LoginRequest
import saas.identity.shared.models.LoginResponse
import saas.identity.shared.models.SysUser
import saas.identity.shared.models.SysUserStatus
import saas.identity.shared.models.TenantMemberStatus
import saas.identity.shared.models.TenantMembership

/**
 * M01.F04 密码登录 + 失败锁定 + 登出（fastapi auth_impl 镜像，spec §4 十条）。 语义锚点：401 同语义不泄露存在性 / 5 次→锁 15min→423
 * 空体 / tenantId=首个 active membership 且租户 active / availableTenants=active ∩ 订阅（空集不过滤）。
 */
class AuthApiServiceImpl(private val db: R2dbcDatabase, private val jwt: JwtIssuer) :
    AuthApiDelegate {

  override suspend fun sessionsLogin(loginRequest: LoginRequest, call: RoutingCall): LoginResponse {
    val user = findUser(loginRequest.username) ?: throw InvalidCredentialsError()
    // M01.F04.I02 —— 锁定窗口检查（窗口内密码正确也 423）
    user.lockedUntil
        ?.takeIf { it.isAfter(OffsetDateTime.now()) }
        ?.let { throw LockedAccountError(it) }
    verifyPasswordOrRecord(user, loginRequest.password)
    val outcome = resolveAccessibleMemberships(user.id, loginRequest.clientId)
    val accessToken = jwt.issueAccessToken(user.id, outcome.currentTenantId)
    val refreshToken =
        TokenPair(db).persist(user.id, outcome.currentTenantId, loginRequest.clientId)
    return buildLoginResponse(user, outcome, loginRequest.clientId, accessToken, refreshToken)
  }

  override suspend fun sessionsLogout(call: RoutingCall) {
    // M01.F04.I06：本地 no-op（fastapi sessions_logout 镜像）
    return Unit
  }

  // —— 私有件 ——

  /** currentTenantId（订阅过滤前的首个 active）与过滤后的 available 行集。 */
  private data class LoginOutcome(val currentTenantId: Uuid, val available: List<ResultRow>)

  private data class UserRow(
      val id: Uuid,
      val username: String,
      val password: String,
      val email: String?,
      val status: Short,
      val failedAttempts: Int,
      val lockedUntil: OffsetDateTime?,
      val createdAt: OffsetDateTime,
      val updatedAt: OffsetDateTime,
  )

  private suspend fun findUser(username: String): UserRow? =
      suspendTransaction(db) {
            SysUserTable.selectAll().where { SysUserTable.username eq username }.singleOrNull()
          }
          ?.let { row ->
            UserRow(
                id = row[SysUserTable.id],
                username = row[SysUserTable.username],
                password = row[SysUserTable.password],
                email = row[SysUserTable.email],
                status = row[SysUserTable.status],
                failedAttempts = row[SysUserTable.failedAttempts],
                lockedUntil = row[SysUserTable.lockedUntil],
                createdAt = row[SysUserTable.createdAt],
                updatedAt = row[SysUserTable.updatedAt],
            )
          }

  private suspend fun verifyPasswordOrRecord(user: UserRow, rawPassword: String) {
    val matched = Password.matches(rawPassword, user.password)
    suspendTransaction(db) {
      SysUserTable.update({ SysUserTable.id eq user.id }) { st ->
        if (matched) {
          st[failedAttempts] = 0
          st[lockedUntil] = null
        } else {
          val attempts = user.failedAttempts + 1
          st[failedAttempts] = attempts
          if (attempts >= LOCKOUT_THRESHOLD) {
            st[lockedUntil] = OffsetDateTime.now().plusMinutes(LOCKOUT_MINUTES)
          }
        }
      }
    }
    if (!matched) throw InvalidCredentialsError()
  }

  /** active membership 非空 + 首个成员的租户必须 active；订阅空集不过滤（家族 S3 口径）。 */
  private suspend fun resolveAccessibleMemberships(userId: Uuid, clientId: String): LoginOutcome {
    val active =
        suspendTransaction(db) {
              TenantMemberTable.selectAll()
                  .where { TenantMemberTable.userId eq userId }
                  .orderBy(
                      TenantMemberTable.createdAt to SortOrder.ASC,
                      TenantMemberTable.id to SortOrder.ASC,
                  )
                  .toList()
            }
            .filter { it[TenantMemberTable.status] == STATUS_ACTIVE }
    if (active.isEmpty()) throw ForbiddenError("user has no active tenant membership")
    val currentTenantId = active.first()[TenantMemberTable.tenantId]
    val tenant =
        suspendTransaction(db) {
          TenantTable.selectAll().where { TenantTable.id eq currentTenantId }.singleOrNull()
        }
    if (tenant == null || tenant[TenantTable.status] != STATUS_ACTIVE) {
      throw ForbiddenError("tenant unavailable: $currentTenantId")
    }
    val subscribed =
        suspendTransaction(db) {
              TenantApplicationTable.selectAll()
                  .where { TenantApplicationTable.clientId eq clientId }
                  .toList()
            }
            .map { it[TenantApplicationTable.tenantId] }
            .toSet()
    val available = active.filter {
      subscribed.isEmpty() || it[TenantMemberTable.tenantId] in subscribed
    }
    return LoginOutcome(currentTenantId, available)
  }

  private suspend fun buildLoginResponse(
      user: UserRow,
      outcome: LoginOutcome,
      clientId: String,
      accessToken: String,
      refreshToken: String,
  ): LoginResponse =
      LoginResponse(
          user =
              SysUser(
                  id = user.id,
                  username = user.username,
                  email = user.email,
                  status = userStatusFromDb(user.status),
                  createdAt = iso(user.createdAt),
                  updatedAt = iso(user.updatedAt),
              ),
          availableTenants = outcome.available.map { toMembership(it) },
          userId = user.id,
          clientId = clientId,
          currentTenantId = outcome.currentTenantId,
          accessToken = accessToken,
          refreshToken = refreshToken,
          tokenType = "Bearer",
          expiresIn = LOGIN_EXPIRES_IN,
      )

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

  private fun userStatusFromDb(value: Short): SysUserStatus =
      when (value.toInt()) {
        2 -> SysUserStatus.invited
        0 -> SysUserStatus.disabled
        else -> SysUserStatus.active
      }

  private fun memberStatusFromDb(value: Short): TenantMemberStatus =
      when (value.toInt()) {
        2 -> TenantMemberStatus.invited
        STATUS_SUSPENDED -> TenantMemberStatus.suspended
        0 -> TenantMemberStatus.disabled
        else -> TenantMemberStatus.active
      }

  companion object {
    const val LOCKOUT_THRESHOLD = 5
    const val LOCKOUT_MINUTES = 15L
    // springboot 登录响应字面 3600（JWT exp 本身走 TTL），家族口径
    const val LOGIN_EXPIRES_IN = 3600
    const val STATUS_ACTIVE: Short = 1
    const val STATUS_SUSPENDED = 3
  }
}

/** ISO_OFFSET_DATE_TIME 序列化（家族 DTO createdAt/joinedAt 字符串口径）。 */
private fun iso(value: OffsetDateTime): String = value.format(ISO_OFFSET_DATE_TIME)
