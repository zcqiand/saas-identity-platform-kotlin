# SaaS 身份平台 Kotlin/Ktor 端

SaaS 身份平台 Kotlin/Ktor 端（`saas-identity-platform-kotlin`）——一句话系统定位（待补）。

本仓为《（书稿信息待补）》案例（待补）的可运行配套工程，是书稿代码块的 **source of truth**。

## 快速开始

```bash
# 前置：gradle 在 PATH（本机 gradle-9.8.1，见 memory: gate-ops-ledger）
gradle ktfmtCheck       # L1 格式
gradle detekt           # L2 静态
gradle compileKotlin    # L3 编译
gradle test             # L4 测试
gradle run              # 本地起服务（mainClass io.xr.harness.ApplicationKt，SERVER_PORT=5109）
```

## 功能特性

按特性分点描述核心功能（见 `docs/functions/function-tree.md`）。

## 技术栈

| 技术 | 版本 |
| :--- | :--- |
| Kotlin / Ktor | 版本全集见 version-lock.json（gradle 9.8.1 / detekt / ktfmt / exposed…） |

> 依赖版本与 `version-lock.json` 的 `version_lock` 一致，不引入 lock 外的库。

## 配套书籍及章节映射

| 章 | 主题 | 对应源文件 |
| :--- | :--- | :--- |
| （待补） | | |

### 书一《Kotlin 从入门到项目实践》（卷四）

- 书稿定位：xr-know-020，第 32、33、34 章正文代码块挂本仓 `source=` 锚点
- 配套版本：`v0.2.1-20261025`（registry 冻结 tag，代码以此为准）

| 章 | 章题 | 对应源文件 |
| :--- | :--- | :--- |
| 32 | 项目二启动：SaaS 多租户多应用身份平台设计 | `src/main/kotlin/saas/identity/entity/TenantTable.kt` + `src/main/kotlin/saas/identity/entity/TenantMemberTable.kt` + `src/main/kotlin/saas/identity/entity/TenantMemberRoleTable.kt` + `src/main/kotlin/saas/identity/shared/models/Tenant.kt` + `src/main/kotlin/saas/identity/shared/models/TenantMembership.kt` + `src/main/kotlin/saas/identity/impl/AdminTenantsApiServiceImpl.kt` + `src/main/kotlin/saas/identity/impl/MeApiServiceImpl.kt` |
| 33 | 项目二：租户管理与数据隔离 | `src/main/kotlin/saas/identity/impl/AdminTenantsApiServiceImpl.kt` + `src/main/kotlin/saas/identity/impl/MeApiServiceImpl.kt` + `src/main/kotlin/saas/identity/entity/TenantTable.kt` + `src/main/kotlin/saas/identity/entity/TenantMemberTable.kt` + `src/main/kotlin/saas/identity/shared/models/SwitchTenantResponse.kt` + `src/test/kotlin/io/xr/harness/AdminTenantsListTest.kt` + `src/test/kotlin/io/xr/harness/MeApiTest.kt` |
| 34 | 项目二：认证、SSO 与 RBAC 菜单授权 | `src/main/kotlin/saas/identity/impl/AuthApiServiceImpl.kt` + `src/main/kotlin/saas/identity/impl/OauthApiServiceImpl.kt` + `src/main/kotlin/saas/identity/impl/MeApiServiceImpl.kt` + `src/main/kotlin/saas/identity/impl/Password.kt` + `src/main/kotlin/saas/identity/impl/TokenPair.kt` + `src/main/kotlin/saas/identity/impl/JwtIssuer.kt` + `src/main/kotlin/saas/identity/entity/OauthCodeTable.kt` + `src/main/kotlin/saas/identity/entity/OauthAccessTokenTable.kt` + `src/main/kotlin/saas/identity/entity/OauthRefreshTokenTable.kt` + `src/main/kotlin/saas/identity/entity/SysMenuTable.kt` + `src/main/kotlin/saas/identity/entity/SysRoleMenuTable.kt` + `src/main/kotlin/saas/identity/shared/models/EffectiveMenuNode.kt` + `src/test/kotlin/io/xr/harness/AuthLoginTest.kt` + `src/test/kotlin/io/xr/harness/OauthApiTest.kt` + `src/test/kotlin/io/xr/harness/MeApiTest.kt` + `src/test/kotlin/io/xr/harness/IdpFlowTest.kt` |
## 快速链接

- [CLAUDE.md](CLAUDE.md) — 开发约定与编码规范
- [系统架构.md](docs/ARCHITECTURE.md) — 结构 / 边界 / 数据流 / 决策
- [功能规格.md](docs/functions/function-tree.md) — 功能名称、描述与验收标准
- [未来开发计划](PLAN.md) — 待办与迭代方向
- [更新日志](CHANGELOG.md) — 版本变更记录
