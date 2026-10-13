package io.xr.harness

import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
  // 端口 fail-fast：缺 SERVER_PORT / 非整数直接抛——禁止 env 默认值兜底（suite-hard-rules §1）。
  val port =
      System.getenv("SERVER_PORT")?.toIntOrNull()
          ?: error(
              "SERVER_PORT 未设置或不是整数。" +
                  "禁止默认值兜底（suite-hard-rules §1）；dev 值见 .env.example（saas=5109 / lab=5209）。",
          )

  // 家族探活契约形状：GET /health → {"ok":true,"mode":"..."}（agent 家族同款）。
  // 骨架只有这一个端点；业务 API 一律等 shared 契约生成物（suite-hard-rules §4）。
  embeddedServer(Netty, port = port) {
        routing {
          get("/health") {
            call.respondText("""{"ok":true,"mode":"ktor"}""", ContentType.Application.Json)
          }
        }
      }
      .start(wait = true)
}
