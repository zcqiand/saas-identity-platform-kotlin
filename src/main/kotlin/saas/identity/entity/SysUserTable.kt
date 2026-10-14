// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 saas_dev，禁手改。
// 字段语义见 saas-identity-platform-shared/src/db/schema.ts sysUser。
package saas.identity.entity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object SysUserTable : Table("sys_user") {
  val id = uuid("id")
  val username = varchar("username", 64)
  val password = varchar("password", 255)
  val email = varchar("email", 128).nullable()
  val mobile = varchar("mobile", 32).nullable()
  val status = short("status")
  val createdAt = timestampWithTimeZone("created_at")
  val updatedAt = timestampWithTimeZone("updated_at")
  val failedAttempts = integer("failed_attempts")
  val lockedUntil = timestampWithTimeZone("locked_until").nullable()
  override val primaryKey = PrimaryKey(id)
}
