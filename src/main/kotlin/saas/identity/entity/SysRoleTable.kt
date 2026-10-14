// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts sysRole。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object SysRoleTable : Table("sys_role") {
  val id = uuid("id")
  val tenantId = uuid("tenant_id")
  val clientId = varchar("client_id", 64)
  val roleCode = varchar("role_code", 64)
  val roleName = varchar("role_name", 64)
  val description = varchar("description", 255).nullable()
  val isPreset = bool("is_preset")
  val status = short("status")
  val createdAt = timestampWithTimeZone("created_at")
  val updatedAt = timestampWithTimeZone("updated_at")
  override val primaryKey = PrimaryKey(id)
}
