package saas.identity.impl

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import java.util.Date
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/** HS256 签发/验签（fastapi JwtIssuer 镜像）：claims iss/aud/sub/tenant_id/jti + iat/nbf/exp。 */
class JwtIssuer(
    signingKey: String,
    private val issuer: String,
    private val audience: String,
    val ttlSeconds: Long,
) {

  private val key: SecretKey

  init {
    val bytes = signingKey.toByteArray()
    require(bytes.size >= HS256_MIN_KEY_BYTES) {
      "JWT_SIGNING_KEY must be >=32 bytes for HS256（家族硬约束）"
    }
    key = SecretKeySpec(bytes, "HmacSHA256")
  }

  fun issueAccessToken(userId: Uuid, tenantId: Uuid): String {
    val epoch = System.currentTimeMillis() / MILLIS_PER_SECOND
    return Jwts.builder()
        .issuer(issuer)
        .audience()
        .add(audience)
        .and()
        .subject(userId.toString())
        .claim("tenant_id", tenantId.toString())
        .id(Uuid.random().toString())
        .issuedAt(Date(epoch * MILLIS_PER_SECOND))
        .notBefore(Date(epoch * MILLIS_PER_SECOND))
        .expiration(Date((epoch + ttlSeconds) * MILLIS_PER_SECOND))
        .signWith(key)
        .compact()
  }

  fun decode(token: String): Claims =
      Jwts.parser()
          .verifyWith(key)
          .requireIssuer(issuer)
          .requireAudience(audience)
          .build()
          .parseSignedClaims(token)
          .payload

  private companion object {

    const val HS256_MIN_KEY_BYTES = 32
    const val MILLIS_PER_SECOND = 1000L
  }
}
