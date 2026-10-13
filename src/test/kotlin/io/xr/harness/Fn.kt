package io.xr.harness

/**
 * 声明该测试直接验证的功能子项 ID（java-spring @Fn 的 Kotlin 同源移植）。
 *
 * 契约（机器管不了的部分，靠自觉）：
 * - 只在测试直接验证该子项可观察行为时挂 ID；间接受益与工程设施测试不挂。
 * - 被 skip 的测试其 fns 由 HarnessTraceListener 在源头清空——"声明覆盖但不执行"物理上不可能。
 *
 * 用法：
 *
 * ```
 * @Test
 * @Fn(["M01.F01.I14"])
 * fun discountRounding() { ... }
 * ```
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Fn(val value: Array<String>)
