// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts tenantApplication。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object TenantApplicationTable : Table("tenant_application") {
  val id = uuid("id")
  val tenantId = uuid("tenant_id")
  val clientId = varchar("client_id", 64)
  val status = short("status")
  val expireTime = timestampWithTimeZone("expire_time").nullable()
  val createdAt = timestampWithTimeZone("created_at")
  override val primaryKey = PrimaryKey(id)
}
