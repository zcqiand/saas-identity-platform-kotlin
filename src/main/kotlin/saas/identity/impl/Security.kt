package saas.identity.impl

import io.jsonwebtoken.Claims
import io.ktor.server.routing.RoutingCall
import kotlin.uuid.Uuid
import saas.identity.shared.models.TenantMemberStatus

/** me/oauth/admin 族共享安全面（fastapi security.py / oauth_impl 镜像，Phase 6）。 */

/** Bearer 提取 + 验签：无头/非 Bearer 前缀/空串/坏 token（过期·坏签名·格式错）→ InvalidCredentialsError（401）。 */
fun requireBearer(call: RoutingCall, jwt: JwtIssuer): Claims {
  val header = call.request.headers["Authorization"]
  if (header == null || !header.startsWith("Bearer ")) {
    throw InvalidCredentialsError("Bearer token required")
  }
  val token = header.substring("Bearer ".length).trim()
  if (token.isEmpty()) throw InvalidCredentialsError("Bearer token required")
  // 一切验签失败收敛 401（fastapi security.py PyJWTError → InvalidCredentialsError 镜像；终审 I1）
  return runCatching { jwt.decode(token) }
      .getOrElse { throw InvalidCredentialsError("invalid bearer token") }
}

/** fastapi uuid_or_none 镜像：坏 UUID 归 null（不抛），调用方按 op 语义转 400/401/空。 */
fun uuidOrNull(value: Any?): Uuid? = runCatching { Uuid.parse(value.toString()) }.getOrNull()

/** fastapi oauth_impl._redirect_allowed 镜像：csv 白名单精确相等，或前缀 + '?' 边界（RFC 6749 §3.1.2，子路径不算）。 */
internal fun redirectAllowed(whitelistCsv: String, requested: String): Boolean =
    whitelistCsv.split(",").any { entry ->
      val candidate = entry.trim()
      candidate.isNotEmpty() &&
          (requested == candidate ||
              (requested.startsWith(candidate) &&
                  requested.length > candidate.length &&
                  requested[candidate.length] == '?'))
    }

/**
 * fastapi assemble.member_status_from_db 镜像：1=active / 2=invited / 3=suspended / 0 与未知=disabled。
 */
internal fun memberStatusFromDb(value: Short): TenantMemberStatus =
    when (value) {
      1.toShort() -> TenantMemberStatus.active
      2.toShort() -> TenantMemberStatus.invited
      3.toShort() -> TenantMemberStatus.suspended
      else -> TenantMemberStatus.disabled
    }
