// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts sysMenu。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object SysMenuTable : Table("sys_menu") {
  val id = uuid("id")
  val clientId = varchar("client_id", 64)
  val parentId = uuid("parent_id")
  val title = varchar("title", 64)
  val type = short("type")
  val path = varchar("path", 255).nullable()
  val component = varchar("component", 255).nullable()
  val perms = varchar("perms", 128).nullable()
  val icon = varchar("icon", 128).nullable()
  val sortOrder = integer("sort_order")
  val status = short("status")
  val createdAt = timestampWithTimeZone("created_at")
  override val primaryKey = PrimaryKey(id)
}
