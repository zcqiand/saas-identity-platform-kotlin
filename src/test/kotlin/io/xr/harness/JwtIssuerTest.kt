package io.xr.harness

import io.jsonwebtoken.Jwts
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import saas.identity.impl.JwtIssuer

private const val KEY = "dev-key-32-bytes-minimum-length!"

class JwtIssuerTest {

  private fun issuer(ttl: Long = 3600) =
      JwtIssuer(KEY, "saas-identity-platform", "saas-identity-platform-clients", ttl)

  @Test
  fun issuesHs256TokenWithFamilyClaims() {
    val token =
        issuer()
            .issueAccessToken(
                Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                Uuid.parse("11111111-1111-1111-1111-111111111111"),
            )
    val claims =
        Jwts.parser()
            .verifyWith(SecretKeySpec(KEY.toByteArray(), "HmacSHA256"))
            .requireIssuer("saas-identity-platform")
            .requireAudience("saas-identity-platform-clients")
            .build()
            .parseSignedClaims(token)
            .payload
    assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", claims.subject)
    assertEquals(
        "11111111-1111-1111-1111-111111111111",
        claims.get("tenant_id", String::class.java),
    )
    assertEquals(3600L, (claims.expiration.time - claims.issuedAt.time) / 1000)
    assertEquals(claims.issuedAt.time, claims.notBefore.time)
  }

  @Test
  fun ttlOverrideShortensExpiry() {
    val token = issuer(ttl = 60).issueAccessToken(Uuid.random(), Uuid.random())
    val claims =
        Jwts.parser()
            .verifyWith(SecretKeySpec(KEY.toByteArray(), "HmacSHA256"))
            .build()
            .parseSignedClaims(token)
            .payload
    assertEquals(60L, (claims.expiration.time - claims.issuedAt.time) / 1000)
  }

  @Test
  fun shortKeyFailsFastAtConstruction() {
    assertThrows(IllegalArgumentException::class.java) { JwtIssuer("short", "iss", "aud", 3600) }
  }
}
