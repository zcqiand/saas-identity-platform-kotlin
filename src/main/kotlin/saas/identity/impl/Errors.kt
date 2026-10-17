package saas.identity.impl

import java.time.OffsetDateTime

/** 登录链类型化异常（fastapi impl/errors.py 镜像）：StatusPages 按 httpStatus 映射，见 Application.kt。 */
class InvalidCredentialsError(message: String = "invalid credentials") : RuntimeException(message)

class LockedAccountError(val lockedUntil: OffsetDateTime) :
    RuntimeException("account locked until ${lockedUntil.toInstant()}")

class ForbiddenError(message: String) : RuntimeException(message)

class BadRequestError(message: String) : RuntimeException(message)

/** switch-tenant/资源不存在 404 族（fastapi errors.py NotFoundError 镜像，Phase 6 扩面）。 */
class NotFoundError(message: String) : RuntimeException(message)
