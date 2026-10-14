// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts oauthAccessToken。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object OauthAccessTokenTable : Table("oauth_access_token") {
  val id = uuid("id")
  val tokenId = varchar("token_id", 128)
  val accessToken = text("access_token")
  val clientId = varchar("client_id", 64)
  val userId = uuid("user_id").nullable()
  val tenantId = uuid("tenant_id").nullable()
  val scope = varchar("scope", 255).nullable()
  val tokenType = varchar("token_type", 32)
  val expiresAt = timestampWithTimeZone("expires_at")
  val revoked = bool("revoked")
  val createdAt = timestampWithTimeZone("created_at")
  override val primaryKey = PrimaryKey(id)
}
