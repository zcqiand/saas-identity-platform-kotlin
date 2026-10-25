package io.xr.harness

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import saas.identity.impl.AdminTenantsApiServiceImpl
import saas.identity.impl.AuthApiServiceImpl
import saas.identity.impl.BadRequestError
import saas.identity.impl.ForbiddenError
import saas.identity.impl.InvalidCredentialsError
import saas.identity.impl.JwtIssuer
import saas.identity.impl.LockedAccountError
import saas.identity.impl.MeApiServiceImpl
import saas.identity.impl.NotFoundError
import saas.identity.impl.OauthApiServiceImpl
import saas.identity.impl.TokenPair
import saas.identity.shared.api.AdminTenantsApi
import saas.identity.shared.api.AuthApi
import saas.identity.shared.api.MeApi
import saas.identity.shared.api.OauthApi
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
  installErrorMapping()
  delegates(
      AppDelegates(
          authApiDelegate = AuthApiServiceImpl(db, jwt),
          adminTenantsApiDelegate = AdminTenantsApiServiceImpl(db, jwt),
          meApiDelegate = MeApiServiceImpl(db, jwt),
          oauthApiDelegate = OauthApiServiceImpl(db, jwt, TokenPair(db)),
      )
  )
  routing {
    // 家族探活契约形状：GET /health → {"ok":true,"mode":"ktor"}。
    get("/health") {
      call.respondText("""{"ok":true,"mode":"ktor"}""", ContentType.Application.Json)
    }
    // Swagger 文档面（REQ-2026-003）：基础设施端点，不进契约面/功能树（与 /health 同模式，
    // springboot/aspnetcore「根路径跳文档」先例）。内容=shared 契约生成物
    // （resources/api-docs/，gen-shared.sh 落盘），零手写注解零漂移。
    get("/") { call.respondRedirect("/api-docs") }
    staticResources("/api-docs", "api-docs")
    // webjar 指纹 ×2（REQ-2026-003）：① basePackage 按 Java 包名点分，版本号 "5.34.0"
    // 写进 basePackage 会被劈成 5/34/0 → 404；② 版本段也不能写进 remotePath——tailcard
    // 只捕获 remotePath 之后的段。正确形态 = 路由挂前缀、版本走请求 URL
    // （/swagger-ui/5.34.0/...，webjar 原生布局）。bump swagger-ui-dist 时同 commit 改
    // build.gradle 依赖与本注释、api-docs/index.html 两处引用。
    staticResources("/swagger-ui", "META-INF/resources/webjars/swagger-ui-dist")
    AuthApi()
    AdminTenantsApi()
    MeApi()
    OauthApi()
  }
}

fun main() {
  val cfg = appConfigFromEnv()
  embeddedServer(Netty, port = cfg.serverPort) { harnessModule(cfg) }.start(wait = true)
}

/** 错误只走异常映射（Review Focus 1：impl 永不直接 respond，防双 respond 崩）。 */
private fun Application.installErrorMapping() {
  install(StatusPages) {
    // message 透传（家族口径：springboot e.getMessage() / rails e.message / fastapi exc.message
    // 全透传具体 message——5.112②③ 终审 defer minor 收口，kotlin 固定 "invalid credentials" 是超集漂移）
    exception<InvalidCredentialsError> { call, cause ->
      call.respond(
          HttpStatusCode.Unauthorized,
          ErrorResponse(
              code = "INVALID_CREDENTIALS",
              message = cause.message ?: "invalid credentials",
          ),
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
    exception<NotFoundError> { call, cause ->
      call.respond(
          HttpStatusCode.NotFound,
          ErrorResponse(code = "NOT_FOUND", message = cause.message ?: "not found"),
      )
    }
    exception<Throwable> { call, _ ->
      call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse(code = "INTERNAL", message = "internal error"),
      )
    }
  }
}
