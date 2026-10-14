// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts tenantMemberRole。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table

object TenantMemberRoleTable : Table("tenant_member_role") {
  val memberId = uuid("member_id")
  val roleId = uuid("role_id")
  override val primaryKey = PrimaryKey(memberId, roleId)
}
