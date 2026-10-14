// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts sysRoleMenu。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table

object SysRoleMenuTable : Table("sys_role_menu") {
  val roleId = uuid("role_id")
  val menuId = uuid("menu_id")
  override val primaryKey = PrimaryKey(roleId, menuId)
}
