# REQ-2026-001 Kotlin/Ktor 端进 deploy 链（prod 上线）

| 项 | 值 |
|---|---|
| 提出人 | zcqiand |
| 提出日期 | 2026-10-25 |
| 优先级 | P1 |
| 状态 | 待评审（后置：kotlin 业务功能先完成，见澄清记录 #5） |
| 关联 ADR | — |

## 1. 需求描述

> 用户原话（2026-10-25）：「saas+lab Kotlin deploy 链立成一项需求排期」

**理解**：本仓（`saas-identity-platform-kotlin`，Ktor 后端）与 `lab-management-system-kotlin` 一起接入家族 deploy 链。当前家族档案值表标注 X09 槽位「骨架进家族，**未进 deploy 链**」——本仓无 Dockerfile、无 CI workflow、无 deploy 脚本，prod 面不存在。本需求补齐 deploy 五件套（照 saas-flutter 2026-10-10 进链先例与后端栈仓姿态），打通 tag push → 镜像构建 → VPS 起容器 → prod 域名探活的全链。

槽位与姿态（suite 家族档案已定，非本需求决策）：X09 = `saas-kotlin.xiangru.uk`，host 端口 **5109**，host=container 单层（沿用 X05/X06 姿态），`SERVER_PORT` 全家族统一 key。

**本需求为纯工程设施**：不新增、不变更、不废弃任何业务功能；两仓 `function-tree.md` 均不动。

### 澄清记录

| 疑问 | 澄清结论 | 澄清人 | 日期 |
|---|---|---|---|
| 「Kotlin」指哪些仓？ | 指 `-kotlin`（Ktor 后端）两仓；`-android`（X12）分发面是 APK、无 deploy 链概念，不在本需求内。依据：家族档案仅 ktor 槽位标注「未进 deploy 链」。 | Claude 推定（如与意图不符请指出） | 2026-10-25 |
| 「一项需求」怎么立档？ | 两仓各立一份镜像 REQ（本文件与 lab 仓 REQ-2026-001），作为同一个排期项。 | zcqiand（三选一选定） | 2026-10-25 |
| 验收边界画到哪？ | 全链到 prod：tag push 后 prod 域名探活 200 才算做到。 | zcqiand（选定） | 2026-10-25 |
| 优先级？ | P1（kotlin 栈业务功能已开发中，无 deploy 链则无法 GA 翻转）。 | zcqiand（选定） | 2026-10-25 |
| 排期定序？ | **kotlin 栈业务功能先完成，本需求后置**：本需求 T-1 的启动条件 = 两仓 kotlin function-tree 现存「开发中」功能子项全部 GA 翻转（或经人裁收口）。在业务功能收口前不启动 deploy 链实现。 | zcqiand（2026-10-25 定序） | 2026-10-25 |

## 2. 验收标准

| 编号 | 场景（给定） | 操作（当） | 预期（则） |
|---|---|---|---|
| AC-1 | deploy 五件套合入主分支 | 打 tag push | CI 构建并推送 `latest` + `<tag>` 双份镜像，自动部署，`https://saas-kotlin.xiangru.uk/health` 返回 200 `{"ok":true,"mode":"ktor"}` |
| AC-2 | saas 侧首次部署成功 | 用旧 tag 重跑 deploy 脚本 | prod 回到上一版本，探活恢复 200（回滚通道存在且演练过一次） |
| AC-3 | 本需求收尾 | 查看家族档案 `docs/families/saas.md` X09 行 | 不再含「未进 deploy 链」字样，与实际部署状态一致，且与代码同 commit |

## 3. 任务拆解

> 2026-10-25 定序：kotlin 业务功能先完成，本节全部任务**后置启动**（启动条件见澄清记录 #5）。

| 任务 ID | 任务描述 | 类型 | 负责人 | 预估 | 状态 |
|---|---|---|---|---|---|
| T-1 | saas-kotlin deploy 五件套落地（Dockerfile、CI workflow、deploy 脚本、nginx vhost 模板、探活接线；母本照后端栈仓既有脚本） | 工程 | — | — | 已完成（2026-10-25，五件套+L0.5 parity 验证） |
| T-2 | lab-kotlin 五件套（镜像 T-1，值差异仅端口/域名/库名） | 工程 | — | — | 已完成（2026-10-25，五件套+L0.5 parity 验证） |
| T-3 | saas 侧首次 tag push 真部署 + prod 探活 + 回滚演练（AC-1/AC-2） | 工程 | — | — | 待开始 |
| T-4 | lab 侧同 T-3 | 工程 | — | — | 待开始 |
| T-5 | 家族档案值表翻转（saas.md / lab.md X09 行）+ 两仓台账收账，同 commit | 文档 | — | — | 待开始 |

## 4. 功能影响（需求与功能对齐的唯一位置）

**无。** 本需求是纯工程设施（deploy 链），不改任何业务功能的界面、接口、行为、权限。按 alignment 细则「工程设施的测试（门禁、构建、CI）不挂任何业务 ID」「重构但行为不变不填影响表」，本表为空；两仓 function-tree 均不变更。

| 功能 ID | 功能名称 | 影响类型 | 说明 | 关联任务 |
|---|---|---|---|---|
| — | — | — | 无业务功能影响 | — |

## 5. 流程影响

无。不改任何业务流程步骤；仅新增「tag push → prod」交付管道。

## 6. 风险与回滚

| 风险 | 影响面 | 缓解 | 回滚方式 |
|---|---|---|---|
| prod DB 接线：ktor 依赖 PostgreSQL，prod 须连 `saas_prod`（远程 `100.79.128.25`，prod-only 政策，禁在此机建 dev/test 类库）；env 契约须走家族统一 key（`SERVER_PORT` / `DATABASE_*`），禁 env 兜底 | 部署后业务接口不可用 | env-file 自举照家族先例，缺键 fail-fast；`/health` 不依赖 DB，业务可用性另行用真实路由探针验 | 旧 tag 重跑 deploy 脚本 |
| `/health` 探活绿 ≠ 业务可用（无 DB 依赖） | 假绿上线 | T-3 验收时加打一条真实业务路由（预期 401/403 形状）确认链路 | 同上 |
| VPS 主机端口 5109 首次启用，可能被占用 | 容器起不来 | 部署前 `ss -ltn` 核对端口空闲 | 释放占用或按家族扩展段换槽（须先改值表，两处同 commit） |
| GitHub Secrets（DOCKER_USERNAME/PASSWORD、VPS_*）若未覆盖本仓，CI 起不来 | CI 失败 | 首次 tag 前核验 secrets 配置（agent 家族六仓先例已配，家族仓按需补） | — |
| 五件套值不同步（Dockerfile EXPOSE、deploy `-p`、vhost `proxy_pass` 三处端口） | 探针 connection refused | 照「改端口三处同步」纪律，一处 commit 内完成；deploy-chain 指纹在案 | 修正后重跑脚本 |
