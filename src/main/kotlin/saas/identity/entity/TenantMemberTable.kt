// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts tenantMember。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object TenantMemberTable : Table("tenant_member") {
  val id = uuid("id")
  val tenantId = uuid("tenant_id")
  val userId = uuid("user_id")
  val memberName = varchar("member_name", 64).nullable()
  val isOwner = bool("is_owner")
  val status = short("status")
  val createdAt = timestampWithTimeZone("created_at")
  val updatedAt = timestampWithTimeZone("updated_at")
  override val primaryKey = PrimaryKey(id)
}
