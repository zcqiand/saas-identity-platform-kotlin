package io.xr.harness

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import saas.identity.impl.AuthApiServiceImpl
import saas.identity.impl.BadRequestError
import saas.identity.impl.ForbiddenError
import saas.identity.impl.InvalidCredentialsError
import saas.identity.impl.JwtIssuer
import saas.identity.impl.LockedAccountError
import saas.identity.shared.api.AuthApi
import saas.identity.shared.infrastructure.AppDelegates
import saas.identity.shared.infrastructure.BadParameterException
import saas.identity.shared.infrastructure.delegates
import saas.identity.shared.models.ErrorResponse

fun Application.harnessModule(cfg: AppConfig) {
  val db = R2dbcDatabase.connect(cfg.databaseUrl.toR2dbcUrl())
  val jwt = JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtAudience, cfg.jwtTtlSeconds)

  install(ContentNegotiation) { json() }
  // 生成区 AuthApi 用 typed routing（post<Paths.…>），必须装 Resources 插件
  install(Resources)
  install(StatusPages) {
    // 错误只走异常映射（Review Focus 1：impl 永不直接 respond，防双 respond 崩）
    exception<InvalidCredentialsError> { call, _ ->
      call.respond(
          HttpStatusCode.Unauthorized,
          ErrorResponse(code = "INVALID_CREDENTIALS", message = "invalid credentials"),
      )
    }
    // 423 空体（springboot body(null) 家族口径，LockedAccountResponse 契约模型未启用）
    exception<LockedAccountError> { call, _ -> call.respond(HttpStatusCode.Locked) }
    exception<ForbiddenError> { call, cause ->
      call.respond(
          HttpStatusCode.Forbidden,
          ErrorResponse(code = "FORBIDDEN", message = cause.message ?: "forbidden"),
      )
    }
    exception<BadRequestError> { call, cause ->
      call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(code = "BAD_REQUEST", message = cause.message ?: "bad request"),
      )
    }
    // 生成区 validate()（delegatePattern 附带）对契约约束违反抛 BadParameterException——
    // 形状并入 BAD_REQUEST（家族统一错误形状；ktor-conventions 规则 2）
    exception<BadParameterException> { call, cause ->
      call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(code = "BAD_REQUEST", message = cause.message ?: "bad request"),
      )
    }
    exception<BadRequestException> { call, _ ->
      call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(code = "BAD_REQUEST", message = "malformed request body"),
      )
    }
    exception<Throwable> { call, _ ->
      call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse(code = "INTERNAL", message = "internal error"),
      )
    }
  }
  delegates(AppDelegates(authApiDelegate = AuthApiServiceImpl(db, jwt)))
  routing {
    // 家族探活契约形状：GET /health → {"ok":true,"mode":"ktor"}。
    get("/health") {
      call.respondText("""{"ok":true,"mode":"ktor"}""", ContentType.Application.Json)
    }
    AuthApi()
  }
}

fun main() {
  val cfg = appConfigFromEnv()
  embeddedServer(Netty, port = cfg.serverPort) { harnessModule(cfg) }.start(wait = true)
}
