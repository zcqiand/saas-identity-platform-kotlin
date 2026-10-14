// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts oauthClient。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object OauthClientTable : Table("oauth_client") {
  val id = uuid("id")
  val clientId = varchar("client_id", 64)
  val clientSecret = varchar("client_secret", 255)
  val clientName = varchar("client_name", 128)
  val grantTypes = varchar("grant_types", 255)
  val redirectUris = text("redirect_uris")
  val scopes = varchar("scopes", 255).nullable()
  val accessTokenValidity = integer("access_token_validity")
  val refreshTokenValidity = integer("refresh_token_validity")
  val autoApprove = bool("auto_approve")
  val status = short("status")
  val createdAt = timestampWithTimeZone("created_at")
  val updatedAt = timestampWithTimeZone("updated_at")
  override val primaryKey = PrimaryKey(id)
}
