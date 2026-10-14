// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts oauthRefreshToken。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object OauthRefreshTokenTable : Table("oauth_refresh_token") {
  val id = uuid("id")
  val refreshToken = varchar("refresh_token", 128)
  val accessTokenId = uuid("access_token_id")
  val clientId = varchar("client_id", 64)
  val userId = uuid("user_id").nullable()
  val tenantId = uuid("tenant_id").nullable()
  val expiresAt = timestampWithTimeZone("expires_at")
  val revoked = bool("revoked")
  val createdAt = timestampWithTimeZone("created_at")
  override val primaryKey = PrimaryKey(id)
}
