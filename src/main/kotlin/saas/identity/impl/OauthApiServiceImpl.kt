package saas.identity.impl

import io.ktor.server.routing.RoutingCall
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import saas.identity.entity.OauthClientTable
import saas.identity.entity.OauthCodeTable
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

  override suspend fun oAuthToken(tokenRequest: TokenRequest, call: RoutingCall): TokenResponse =
      TODO("Task 7/8")

  companion object {
    const val CODE_TTL_MINUTES = 5L
  }
}
