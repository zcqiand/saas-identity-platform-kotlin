package saas.identity.impl

import io.ktor.server.routing.RoutingCall
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import saas.identity.entity.OauthAccessTokenTable
import saas.identity.entity.OauthClientTable
import saas.identity.entity.OauthCodeTable
import saas.identity.entity.OauthRefreshTokenTable
import saas.identity.shared.api.OauthApiDelegate
import saas.identity.shared.models.AuthorizeCodeRequest
import saas.identity.shared.models.OAuthAuthorize200Response
import saas.identity.shared.models.TokenRequest
import saas.identity.shared.models.TokenResponse

/** OAuth authorize + token 双 grant（fastapi oauth_impl.py 镜像；code 一次性 5 分钟）。 */
class OauthApiServiceImpl(
    private val db: R2dbcDatabase,
    private val jwt: JwtIssuer,
    private val tokenPair: TokenPair,
) : OauthApiDelegate {

  override suspend fun oAuthAuthorize(
      authorizeCodeRequest: AuthorizeCodeRequest,
      call: RoutingCall,
  ): OAuthAuthorize200Response {
    // 顺序镜像 fastapi :95-103：client 查找（400）先于 Bearer（401）
    val client =
        suspendTransaction(db) {
          OauthClientTable.selectAll()
              .where { OauthClientTable.clientId eq authorizeCodeRequest.clientId }
              .firstOrNull()
        } ?: throw BadRequestError("unknown client_id: ${authorizeCodeRequest.clientId}")
    val claims = requireBearer(call, jwt)
    val userId =
        uuidOrNull(claims.subject)
            ?: throw InvalidCredentialsError("Bearer sub required for authorize")
    val tenantId =
        uuidOrNull(claims.get("tenant_id"))
            ?: throw InvalidCredentialsError("JWT tenant_id claim required for authorize")
    if (!redirectAllowed(client[OauthClientTable.redirectUris], authorizeCodeRequest.redirectUri)) {
      throw BadRequestError(
          "INVALID_REDIRECT_URI: ${authorizeCodeRequest.redirectUri} not in oauth_client.redirect_uris",
      )
    }
    val code = "ac_" + Uuid.random()
    val now = OffsetDateTime.now()
    suspendTransaction(db) {
      OauthCodeTable.insert { st ->
        st[id] = Uuid.random()
        st[OauthCodeTable.code] = code
        st[OauthCodeTable.clientId] = client[OauthClientTable.clientId]
        st[OauthCodeTable.userId] = userId
        st[OauthCodeTable.tenantId] = tenantId
        st[OauthCodeTable.redirectUri] = authorizeCodeRequest.redirectUri
        st[OauthCodeTable.scope] = authorizeCodeRequest.scope
        st[expiresAt] = now.plusMinutes(CODE_TTL_MINUTES)
        st[createdAt] = now
      }
    }
    return OAuthAuthorize200Response(code = code, state = authorizeCodeRequest.state)
  }

  override suspend fun oAuthToken(tokenRequest: TokenRequest, call: RoutingCall): TokenResponse {
    val client =
        suspendTransaction(db) {
          OauthClientTable.selectAll()
              .where { OauthClientTable.clientId eq tokenRequest.clientId }
              .firstOrNull()
        } ?: throw BadRequestError("INVALID_CLIENT: unknown client_id")
    return when (tokenRequest.grantType) {
      TokenRequest.GrantType.authorization_code -> exchangeAuthorizationCode(tokenRequest)
      TokenRequest.GrantType.refresh_token -> rotateRefreshToken(tokenRequest)
    }
  }

  private suspend fun exchangeAuthorizationCode(req: TokenRequest): TokenResponse {
    val code =
        req.code
            ?: throw BadRequestError(
                "INVALID_REQUEST: code required for grantType=authorization_code"
            )
    val redirectUri =
        req.redirectUri
            ?: throw BadRequestError(
                "INVALID_REQUEST: redirectUri required for grantType=authorization_code",
            )
    val row =
        suspendTransaction(db) {
          OauthCodeTable.selectAll()
              .where { (OauthCodeTable.code eq code) and (OauthCodeTable.clientId eq req.clientId) }
              .firstOrNull()
        } ?: throw BadRequestError("INVALID_GRANT: code 不存在或已被使用")
    if (row[OauthCodeTable.expiresAt] < OffsetDateTime.now()) {
      deleteCode(code)
      throw BadRequestError("INVALID_GRANT: expired code")
    }
    if (redirectUri != row[OauthCodeTable.redirectUri]) {
      deleteCode(code)
      throw BadRequestError("INVALID_GRANT: redirectUri mismatch")
    }
    val userId = row[OauthCodeTable.userId]
    val tenantId = row[OauthCodeTable.tenantId]
    val scope = row[OauthCodeTable.scope]
    deleteCode(code) // 一次性消费防重放
    return issueTokenResponse(userId, tenantId, req.clientId, scope)
  }

  private suspend fun deleteCode(code: String) {
    suspendTransaction(db) { OauthCodeTable.deleteWhere { OauthCodeTable.code eq code } }
  }

  private suspend fun issueTokenResponse(
      userId: Uuid,
      tenantId: Uuid,
      clientId: String,
      rawScope: String?,
  ): TokenResponse {
    val refresh = tokenPair.persist(userId, tenantId, clientId, rawScope)
    return TokenResponse(
        accessToken = jwt.issueAccessToken(userId, tenantId),
        refreshToken = refresh,
        tokenType = "Bearer",
        expiresIn = jwt.ttlSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        // msw oracle 语义：scope 空格分隔（RFC 6749 §3.3），库列 csv
        scope = rawScope?.replace(",", " ")?.trim()?.ifEmpty { null },
        userId = userId.toString(),
        clientId = clientId,
        tenantId = tenantId,
    )
  }

  /** refresh rotate：撤旧签新；client_id 以旧 rt 行绑定值为准不信任请求体（springboot 参照）。 */
  private suspend fun rotateRefreshToken(req: TokenRequest): TokenResponse {
    val rtValue =
        req.refreshToken
            ?: throw BadRequestError(
                "INVALID_REQUEST: refreshToken required for grantType=refresh_token",
            )
    val rt =
        suspendTransaction(db) {
          OauthRefreshTokenTable.selectAll()
              .where { OauthRefreshTokenTable.refreshToken eq rtValue }
              .firstOrNull()
        } ?: throw BadRequestError("INVALID_GRANT: refreshToken 不存在或已被使用")
    if (rt[OauthRefreshTokenTable.revoked]) {
      throw BadRequestError("INVALID_GRANT: revoked refresh_token")
    }
    // 列 nullable（DB-First 真源），但本服务签出的 rt 行恒带绑定——缺失属数据损坏，fail-fast 不兜底
    val userId =
        rt[OauthRefreshTokenTable.userId]
            ?: throw BadRequestError("INVALID_GRANT: refresh_token row missing user binding")
    val tenantId =
        rt[OauthRefreshTokenTable.tenantId]
            ?: throw BadRequestError("INVALID_GRANT: refresh_token row missing tenant binding")
    suspendTransaction(db) {
      OauthRefreshTokenTable.update({
        OauthRefreshTokenTable.id eq rt[OauthRefreshTokenTable.id]
      }) {
        it[revoked] = true
      }
    }
    val clientId = rt[OauthRefreshTokenTable.clientId]
    val scope =
        suspendTransaction(db) {
          OauthAccessTokenTable.selectAll()
              .where { OauthAccessTokenTable.id eq rt[OauthRefreshTokenTable.accessTokenId] }
              .firstOrNull()
              ?.get(OauthAccessTokenTable.scope)
        }
    return issueTokenResponse(userId, tenantId, clientId, scope)
  }

  companion object {
    const val CODE_TTL_MINUTES = 5L
  }
}
