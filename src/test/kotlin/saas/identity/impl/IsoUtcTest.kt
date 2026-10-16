package saas.identity.impl

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 终审 deferred minor（Phase 4 搭车修）：iso 必须先归一 UTC 再串化（fastapi as_utc 镜像）， 否则同一时刻跨后端 +08:00 vs +00:00
 * 字符串漂移。
 */
class IsoUtcTest {

  @Test
  fun isoNormalizesNonUtcOffsetToUtc() {
    val plus8 = OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.of("+08:00"))
    // fastapi isoformat 真实形状（恒秒 + +00:00 尾）；计划草稿的 "T00:00+00:00" 系对
    // ISO formatter 行为的误记（Ruling 见 ledger）
    assertEquals("2026-01-01T00:00:00+00:00", iso(plus8))
  }

  @Test
  fun isoKeepsUtcInstantStable() {
    val utc = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    assertEquals("2026-01-01T00:00:00+00:00", iso(utc))
  }
}
