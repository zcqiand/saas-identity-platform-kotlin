package io.xr.harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @Fn 注解 + HarnessTraceListener 的最小贯通测试（java-spring 同源）。
 *
 * 仅作为锚点适配器的 sanity check；它本身不是业务功能。删除或换名时， 记得把 trace.json 里那条记录也相应重命名（listener 按 source path +
 * displayName 记录）。
 */
class FnAnnotationSmokeTest {

  @Test
  @Fn([]) // 不预设功能 ID：脚手架与项目功能清单无关。挂假 ID 会在 L5 悬空（tree 里无此 ID）。
  fun harnessFnAnnotationRoundTripsIntoTrace() {
    // 真正的断言不重要；只要 listener 在走，且 assertion 不挂 → trace 里就有这条记录。
    assertEquals(2, 1 + 1)
  }
}
