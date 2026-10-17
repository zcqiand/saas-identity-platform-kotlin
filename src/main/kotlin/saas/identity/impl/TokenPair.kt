package saas.identity.impl

import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import saas.identity.entity.OauthAccessTokenTable
import saas.identity.entity.OauthClientTable
import saas.identity.entity.OauthRefreshTokenTable

/**
 * token 对落库（fastapi TokenIssuer.persist_token_pair 镜像）：access 行 +1h / refresh 行 +30d。 库列
 * access_token NOT NULL 存占位 "n/a"（真签在 JWT，springboot 同款）。
 */
class TokenPair(private val db: R2dbcDatabase) {

  suspend fun persist(
      userId: Uuid,
      tenantId: Uuid,
      clientId: String,
      scope: String? = null,
  ): String {
    val known =
        suspendTransaction(db) {
          OauthClientTable.selectAll().where { OauthClientTable.clientId eq clientId }.count() > 0
        }
    if (!known) throw BadRequestError("unknown clientId: $clientId")
    val now = OffsetDateTime.now()
    return suspendTransaction(db) {
      val accessId = Uuid.random()
      OauthAccessTokenTable.insert { st ->
        st[id] = accessId
        st[tokenId] = "at_$accessId"
        st[accessToken] = ACCESS_TOKEN_PLACEHOLDER
        st[OauthAccessTokenTable.clientId] = clientId
        st[OauthAccessTokenTable.userId] = userId
        st[OauthAccessTokenTable.tenantId] = tenantId
        // 参数 scope 遮蔽同名列——insert lambda 的 receiver 是表，列须全限定（T3 同款坑）
        st[OauthAccessTokenTable.scope] = scope
        st[tokenType] = "Bearer"
        st[expiresAt] = now.plusSeconds(ACCESS_TTL_SECONDS)
        st[revoked] = false
        st[createdAt] = now
      }
      val refreshValue = "rt_${Uuid.random()}"
      OauthRefreshTokenTable.insert { st ->
        st[id] = Uuid.random()
        st[refreshToken] = refreshValue
        st[accessTokenId] = accessId
        st[OauthRefreshTokenTable.clientId] = clientId
        st[OauthRefreshTokenTable.userId] = userId
        st[OauthRefreshTokenTable.tenantId] = tenantId
        st[expiresAt] = now.plusSeconds(REFRESH_TTL_SECONDS)
        st[revoked] = false
        st[createdAt] = now
      }
      refreshValue
    }
  }

  companion object {
    // springboot TokenIssuer 参照：固定家族口径
    const val ACCESS_TTL_SECONDS = 3600L
    const val REFRESH_TTL_SECONDS = 30L * 24 * 3600
    const val ACCESS_TOKEN_PLACEHOLDER = "n/a"
  }
}
