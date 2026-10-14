// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts oauthCode。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object OauthCodeTable : Table("oauth_code") {
  val id = uuid("id")
  val code = varchar("code", 128)
  val clientId = varchar("client_id", 64)
  val userId = uuid("user_id")
  val tenantId = uuid("tenant_id")
  val redirectUri = varchar("redirect_uri", 500).nullable()
  val scope = varchar("scope", 255).nullable()
  val codeChallenge = varchar("code_challenge", 128).nullable()
  val codeChallengeMethod = varchar("code_challenge_method", 16).nullable()
  val expiresAt = timestampWithTimeZone("expires_at")
  val createdAt = timestampWithTimeZone("created_at")
  override val primaryKey = PrimaryKey(id)
}
