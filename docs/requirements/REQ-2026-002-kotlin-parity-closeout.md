# REQ-2026-002 kotlin 镜像树 GA 收口（9 ID 对齐批）

| 项 | 值 |
|---|---|
| 提出人 | zcqiand |
| 提出日期 | 2026-10-25 |
| 优先级 | P1 |
| 状态 | 已完成 |
| 关联 ADR | — |

## 1. 需求描述

> 背景：REQ-2026-001（deploy 链）定序「kotlin 业务功能先完成」。本仓 9 个功能子项自 Phase 1-5 纵切起已实现并有仓内测试锚定（git log 在案：`30b022d`→`a665705`），但当时未走 REQ 立档，功能树状态停在「开发中」，需求/设计/流程三列无引用。本需求为**对齐收口批**：补登记、补映射、凭人工验收记录走 mirror 免批 GA 翻转（ADR-0042 同款通道）。不写任何新业务代码。

### 澄清记录

| 疑问 | 澄清结论 | 澄清人 | 日期 |
|---|---|---|---|
| 为什么一个 REQ 覆盖 9 个 ID？ | 本批是登记补课而非新功能开发；9 个 ID 的实现/测试已存在，需求锚点一次补齐。 | Claude 提案，人裁照准 | 2026-10-25 |
| M01.F01.I01 / M01.F03.I01 在 shared BASE 仍开发中，能否先翻？ | 能。shared 树口径：「单仓已上线 + BASE 开发中 → 合法」。 | shared 树维护约定 | 2026-10-25 |

### 端点 → 功能 ID 对照

| 端点（ktor 实挂） | 功能 ID | 说明 |
|---|---|---|
| GET /api/v1/admin/tenants | M00.F01.I01 | 平台 admin 分页查看租户（`admin-tenants.tsp:8`） |
| GET /api/v1/me | M01.F01.I01 | 当前用户 whoami（`me.tsp:8`） |
| GET /api/v1/me/tenants | M01.F03.I01 | 列出我的租户成员关系（`me.tsp:13`） |
| POST /api/v1/me/tenants/{tenantId}/switch | M01.F03.I02 | 切换当前租户（`me.tsp:18`） |
| POST /api/v1/auth/login | M01.F04.I01 | 密码登录（5 次→423 语义并入 I01） |
| POST /api/v1/auth/logout | M01.F04.I06 | 登出本地 no-op（fastapi sessions_logout 镜像） |
| POST /api/v1/oauth/authorize | M04.F03.I01 | 授权码签发（`oauth.tsp:10`） |
| POST /api/v1/oauth/token | M04.F03.I02 | OIDC token 双 grant（`oauth.tsp:15`） |
| GET /api/v1/me/menus | M04.F04.I08 | 当前用户有效菜单（`me.tsp:26`） |

## 2. 验收标准

| 编号 | 场景（给定） | 操作（当） | 预期（则） |
|---|---|---|---|
| AC-1 | 本仓 gate 全量跑 | 查看 exit code | 0，L1-L5 全绿 |
| AC-2 | alignment 矩阵生成 | 查 9 个 ID 行 | 需求/设计/流程/测试四列全部非空 |
| AC-3 | GA 翻转提案 `--apply` | 查 function-tree | 9 ID 状态=已上线；`unreachable_shipped` 为空（-kotlin 豁免在案） |

## 3. 任务拆解

| 任务 ID | 任务描述 | 类型 | 负责人 | 预估 | 状态 |
|---|---|---|---|---|---|
| T-1 | 本 REQ 立档 + 端点→ID 对照登记 | 文档 | Claude | — | 已完成 |
| T-2 | design-function-map.md 填 9 行（实取 impl/entity） | 文档 | Claude | — | 已完成 |
| T-3 | flow-function-map.md 填 FLOW-01 主流程 | 文档 | Claude | — | 已完成 |
| T-4 | gate 全量绿 + 证据汇总给人 | 验证 | Claude | — | 已完成 |
| T-5 | 人工验收（人裁停点：人确认「通过」后落记录） | 人裁 | zcqiand | — | 已完成（2026-10-25 通过） |
| T-6 | mirror 提案 `--apply`：9× 开发中→已上线 | 树翻转 | Claude | — | 已完成（11 行翻转：9 I 级 + M00.F01/M04.F03） |

## 4. 功能影响（需求与功能对齐的唯一位置）

| 功能 ID | 功能名称 | 影响类型 | 说明 | 关联任务 |
|---|---|---|---|---|
| M00.F01.I01 | 租户列表 | 变更 | 仅状态翻转（开发中→已上线），行为不变 | T-5/T-6 |
| M01.F01.I01 | 当前用户 whoami | 变更 | 同上 | T-5/T-6 |
| M01.F03.I01 | 列出我的租户成员关系 | 变更 | 同上 | T-5/T-6 |
| M01.F03.I02 | 切换当前租户 | 变更 | 同上 | T-5/T-6 |
| M01.F04.I01 | 密码登录 | 变更 | 同上 | T-5/T-6 |
| M01.F04.I06 | 登出（本地清理 + 全局 SSO） | 变更 | 同上 | T-5/T-6 |
| M04.F03.I01 | 授权码签发 | 变更 | 同上 | T-5/T-6 |
| M04.F03.I02 | OIDC token 端点（双 grant） | 变更 | 同上 | T-5/T-6 |
| M04.F04.I08 | 当前用户有效菜单 | 变更 | 同上 | T-5/T-6 |

## 5. 流程影响

FLOW-01（登录→会话→租户→菜单→OAuth）已登记 `docs/design/flow-function-map.md`，覆盖上表 9 ID。无新增业务流程。

## 6. 测试锚定证据

| 测试文件（src/test/kotlin/io/xr/harness/） | 挂载 ID |
|---|---|
| AdminTenantsListTest.kt | M00.F01.I01 |
| MeApiTest.kt | M01.F01.I01、M01.F03.I01、M01.F03.I02、M04.F04.I08 |
| AuthLoginTest.kt | M01.F04.I01 |
| SessionsLogoutTest.kt | M01.F04.I06 |
| OauthApiTest.kt | M04.F03.I01、M04.F03.I02 |
| IdpFlowTest.kt | M04.F03.I01 + M04.F03.I02 + M01.F03.I01（端到端联合） |

## 7. 人工验收记录

> 本段是 mirror 免批 GA 翻转的凭据（GA_ACCEPT_RE）。验收人确认下述证据后由人落「通过」，模型不得代写。

- 证据包：gate L0-L5 全绿（2026-10-25，22 条目，软告警 12→**0**——`-kotlin` 入 UI 豁免 + 设计/流程映射补齐后清零）；alignment 矩阵 9 ID 需求/设计/流程/测试四列全覆盖；测试锚定清单见 §6
- 结论：**人工验收 2026-10-25 通过（zcqiand）**，9 ID 执行 mirror GA 翻转
