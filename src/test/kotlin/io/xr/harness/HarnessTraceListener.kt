package io.xr.harness

import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * 把 @Fn 标记落成 suite 契约的 .state/trace.json。
 *
 * 仅当 -Dharness.trace=true 时启用（由 suite 的 trace_cmd 注入），避免每次 gradlew test 覆盖工作树里的 trace。被 skip 的测试
 * fns 必须为空数组——listener 在源头抹掉， suite 的契约层会再次复核。这是物理上的不可能性，不是纪律。
 */
class HarnessTraceListener : TestExecutionListener {

  private val rows = mutableListOf<Array<String>>() // [test, fns_csv, inert_bool]
  private var enabled = false
  private lateinit var projectRoot: Path

  override fun testPlanExecutionStarted(plan: TestPlan) {
    val flag = System.getProperty(TRACE_FLAG) ?: System.getenv(TRACE_FLAG)
    enabled = "true".equals(flag, ignoreCase = true)
    projectRoot = Paths.get("").toAbsolutePath().normalize()
  }

  override fun executionFinished(ident: TestIdentifier, result: TestExecutionResult) {
    if (!enabled || !ident.isTest) return
    rows.add(toRow(ident, inert = false))
  }

  override fun executionSkipped(ident: TestIdentifier, reason: String) {
    if (!enabled || !ident.isTest) return
    rows.add(toRow(ident, inert = true))
  }

  override fun testPlanExecutionFinished(plan: TestPlan) {
    if (!enabled) return
    val out = projectRoot.resolve(TRACE_FILE)
    Files.createDirectories(out.parent)
    Files.write(out, render().toByteArray(StandardCharsets.UTF_8))
  }

  private fun toRow(ident: TestIdentifier, inert: Boolean): Array<String> =
      arrayOf(locatePath(ident), if (inert) "" else joinFns(ident), inert.toString())

  /** file + "::" + displayName，kotlin 源树（src/test/kotlin + .kt）。 */
  private fun locatePath(ident: TestIdentifier): String {
    val src = ident.source.orElse(null)
    if (src is MethodSource) {
      val fqcn = src.className
      val simple = fqcn.substringAfterLast('.')
      val dot = fqcn.lastIndexOf('.')
      val pkgPath = if (dot > 0) fqcn.substring(0, dot).replace('.', '/') else ""
      val filePath =
          if (pkgPath.isEmpty()) "src/test/kotlin/$simple.kt"
          else "src/test/kotlin/$pkgPath/$simple.kt"
      return "$filePath::${ident.displayName}"
    }
    return ident.uniqueId
  }

  private fun joinFns(ident: TestIdentifier): String = resolveFns(ident).joinToString(",")

  private fun resolveFns(ident: TestIdentifier): List<String> {
    val src = ident.source.orElse(null) as? MethodSource ?: return emptyList()
    val fns: List<String> =
        try {
          val klass = Class.forName(src.className, false, Thread.currentThread().contextClassLoader)
          val m = findMethod(klass, src.methodName)
          val fn = m?.getAnnotation(Fn::class.java)
          fn?.value?.distinct()?.sorted() ?: emptyList()
        } catch (_: ClassNotFoundException) {
          emptyList()
        }
    return fns
  }

  private fun findMethod(klass: Class<*>, name: String): Method? {
    for (m in klass.declaredMethods) {
      if (m.name == name) return m
    }
    val sup = klass.superclass
    return if (sup != null && sup != Any::class.java) findMethod(sup, name) else null
  }

  private fun render(): String {
    val sb = StringBuilder()
    sb.append("{\n  \"schema\": 1,\n  \"tests\": [\n")
    rows.forEachIndexed { i, r ->
      if (i > 0) sb.append(",\n")
      sb.append("    {\"test\": ").append(quote(r[0]))
      sb.append(", \"fns\": ").append(if (r[1].isEmpty()) "[]" else quoteArray(r[1]))
      sb.append(", \"inert\": ").append(r[2])
      sb.append('}')
    }
    sb.append("\n  ]\n}\n")
    return sb.toString()
  }

  /** JSON 字符串字面量。fn IDs 都是 M0x.F0x.I0x，不会有控制字符，但保险起见转义。 */
  private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
      when (c) {
        '\\',
        '"' -> sb.append('\\').append(c)
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }

  /** fn IDs 由逗号拼接 → JSON 数组。 */
  private fun quoteArray(csv: String): String {
    val sb = StringBuilder()
    sb.append('[')
    csv.split(",").forEachIndexed { i, part ->
      if (i > 0) sb.append(", ")
      sb.append(quote(part))
    }
    sb.append(']')
    return sb.toString()
  }

  companion object {
    private const val TRACE_FLAG = "harness.trace"
    private const val TRACE_FILE = ".state/trace.json"
  }
}
