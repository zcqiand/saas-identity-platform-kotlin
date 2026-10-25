# REQ-2026-003 kotlin 双仓补 Swagger 文档面（SSOT 派生）

| 项 | 值 |
|---|---|
| 提出人 | zcqiand |
| 提出日期 | 2026-10-25 |
| 优先级 | P1 |
| 状态 | 已完成（v0.2.0-20261025 首航全绿，2026-10-25） |
| 关联 ADR | ADR-0045（依赖清单 ktor-bom 收口）；家族先例：springboot v0.1.13 springdoc 根跳转 / aspnetcore Swashbuckle `/swagger` 根跳转 |

## 1. 需求描述

> 用户原话：「kotlin版本没有swagger吗？」→「立项补」。
>
> 背景：家族五后端实测（2026-10-25），springboot（根路径 302 → `/swagger-ui.html`，live
> 200）、aspnetcore（`/swagger`，live 200）、fastapi（内置 `/docs`，live 200）均有在线
> 文档面；ktor 双仓根路径 404、无任何 swagger/openapi 依赖（build.gradle.kts 零相关项，
> openapi-generator jvm-ktor 生成器不产出文档面）。本需求给 saas/lab 两 kotlin 仓补
> **SSOT 派生**的 Swagger 文档面：不引入任何手写注解（手写 = 第二份契约面，必漂移，
> 违反「端点只认 shared 契约生成物」纪律），文档内容直接来自 shared 仓 openapi.yaml
> 生成物（emit:openapi 只产 yaml，无 .json）。

### 方案要点（SSOT 派生，零漂移）

1. `scripts/gen-shared.sh` 生成时把 shared 仓 `generated/openapi/openapi.yaml`
   原样落进 `src/main/resources/api-docs/openapi.yaml`（生成物入库，随仓走）
2. `build.gradle.kts` 加 `org.webjars.npm:swagger-ui-dist`（exact 版本钉死，禁裸写浮动）
3. 应用加静态路由：`/api-docs`（resources 壳页 + `/api-docs/openapi.yaml` 契约）+
   `/swagger-ui`（webjar，版本走请求 URL `/swagger-ui/5.34.0/*`，webjar 原生布局）+
   `GET /` 302 → `/api-docs`（照 springboot/aspnetcore「根路径跳 Swagger」先例；
   实测 ktor 根路径当前 404，无冲突）
4. prod 全开（人裁 2026-10-25，家族先例一致）
5. 两仓镜像改动（同批），CI smoke 探针补 swagger 文档面三件 200

### 澄清记录

| 疑问 | 澄清结论 | 澄清人 | 日期 |
|---|---|---|---|
| 手写 ktor swagger 插件注解还是喂 shared 契约？ | 喂 shared 契约生成物——手写注解=第二份契约面必漂移，违反细则 §2/§4 | Claude 提案，人裁照准（「立项补」针对该方案） | 2026-10-25 |
| prod 公网暴露文档面？ | prod 全开（springboot/aspnetcore live 先例一致） | 人裁（AskUserQuestion「prod 全开」） | 2026-10-25 |
| 功能树要不要登记？ | 不登记。照 aspnetcore 先例：根跳转 Swagger = 基础设施端点，不进契约面/功能树（与 /health 同模式） | Claude 提案，家族先例支撑 | 2026-10-25 |
| 一个 REQ 还是两个？ | 一个 REQ 覆盖两仓镜像改动（同 REQ-2026-001 先例），两仓各落一份同文 REQ | REQ-2026-001 先例 | 2026-10-25 |
| 契约落盘格式 openapi.json 还是 openapi.yaml？ | **openapi.yaml**——shared 仓 emit:openapi 只产 `generated/openapi/openapi.yaml`（无 .json），原样照抄即零转换零漂移；立项稿写 openapi.json 是立项时未核 emit 产物 | Claude 实现时实证（emit 脚本输出清单） | 2026-10-25 |

## 2. 验收标准

| 编号 | 场景（给定） | 操作（当） | 预期（则） |
|---|---|---|---|
| AC-1 | 两仓本地起服务 | `GET /` | 302 → `/api-docs`；`/api-docs` 壳页 200；`/api-docs/openapi.yaml` 200 且为合法 OpenAPI YAML |
| AC-2 | 本地服务 openapi.yaml | 与 shared 仓契约生成物 diff | 内容一致（hash 相同）——SSOT 派生零漂移证据 |
| AC-3 | 两仓 gate 全量 | `python scripts/gate.py -p <仓>` | exit 0，L1-L5 全绿（无 skip 挂 ID） |
| AC-4 | CI tag run | smoke job | swagger 文档面三件探针（`/api-docs` + `/api-docs/openapi.yaml` + `/swagger-ui/5.34.0/swagger-ui.css`）200 进 smoke |
| AC-5 | 首航 tag 推送后 | prod 域名 `GET /` | 302 → `/api-docs` 200；`/api-docs/openapi.yaml` 200 |

## 3. 任务拆解

| 任务 ID | 任务描述 | 类型 | 负责人 | 预估 | 状态 |
|---|---|---|---|---|---|
| T-1 | 两仓 REQ-2026-003 立档（本文档镜像）+ README 台账 | 文档 | Claude | — | 已完成 |
| T-2 | saas 仓：gen-shared.sh 落 openapi.yaml + webjar 依赖 + 路由/根跳转 + gate 全绿 | 代码 | Claude | — | 已完成（gradle 三件套 BUILD SUCCESSFUL） |
| T-3 | lab 仓镜像同构 + gate 全绿 | 代码 | Claude | — | 已完成（gradle 三件套 BUILD SUCCESSFUL） |
| T-4 | 两仓 CI smoke 补 swagger 文档面探针 | 代码 | Claude | — | 已完成 |
| T-5 | 首航：tag push（外发动作，逐项确认）→ prod 探活（AC-5） | 验证 | Claude | — | 已完成（tag 人批 v0.2.0-20261025；CI 全绿；prod 双域四探针 302/200×3，openapi.yaml hash=仓内=shared 零漂移） |
| T-6 | 收账：家族档案（如需）、记忆指纹、suite gitlink | 文档 | Claude | — | 已完成（家族值表零变化不翻档；指纹入 kotlin-ktor 总账；suite gitlink 随本批 bump） |

## 4. 功能影响（需求与功能对齐的唯一位置）

> 无功能树影响：文档面属基础设施端点（不进契约面/功能树，与 /health 同模式，
> aspnetcore v0.1.5 先例注释在案）。不改任何业务端点行为。

| 功能 ID | 功能名称 | 影响类型 | 说明 | 关联任务 |
|---|---|---|---|---|
| （无） | — | — | 基础设施端点，不进功能树 | — |

## 5. 流程影响

无（不动 docs/design/flow-function-map.md 任何流程步骤；文档面不在业务流程内）。

## 6. 风险与回滚

| 风险 | 影响面 | 缓解 | 回滚方式 |
|---|---|---|---|
| gen-shared.sh 变更触碰生成区（L1 门管辖） | 两仓生成区 | wrapper/模板不动，只加「copy openapi.yaml 到 resources」一步；生成后跑门复验 | git revert 生成脚本改动 + 重新生成 |
| webjar 静态路由与现有 route 前缀冲突 | 应用启动 | 根路径实测 404 无占用；`/swagger-ui`、`/api-docs` 均非契约前缀（`/api/v1/*`） | 路由代码单 commit 独立可 revert |
| openapi.yaml 入库后与 shared 漂移 | 文档正确性 | AC-2 hash 比对 + gen-shared.sh 每次生成强制刷新（不手编，untracked/漂移 fail-loud） | 重新跑 gen-shared.sh 即同步 |
| 新依赖破坏现有门（detekt/ktfmt） | L1/L2 门 | 依赖仅静态资源无 Kotlin 代码增量（路由 ~10 行，走门复验） | revert 单 commit |
| 首航 red | prod | 端口/域名不变，无 env 键增减（零 env 变化）；红则旧 tag 显式重跑（REQ-2026-001 AC-2 演练过的回滚路径） | 显式 tag 重跑 deploy 脚本 |
