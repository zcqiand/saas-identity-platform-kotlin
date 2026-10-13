---
name: ktor-conventions
description: ktor 仓的判断类规则。写 route/测试/配置前先读。
---

# ktor 仓判断类规则

能写成 detekt 规则的进 `config/detekt/detekt.yml`（有 exit code）；这里只放机器管不了的判断。

1. **route handler 职责边界**：handler 只做「收参 → 调用服务 → 状态码映射」。
   出现 if/else 分支超过两层、直接拼 SQL/调库，就是业务逻辑进了 handler——提到 `impl/` 或服务类。
2. **错误形状**：业务错误以状态码 + JSON body 表达，不在 handler 里 throw 裸 Exception；
   未捕获异常会变 500 裸栈，家族契约要求统一错误形状（接 shared 契约时对齐）。
3. **coroutine 作用域**：handler 内天然在协程上下文；不要在 handler 里起 GlobalScope 长活协程，
   生命周期归 server 管，泄漏了没有门能抓。
4. **trace 挂 ID**：测试挂 `@Fn(["M0x.F0x.I0x"])` 只限直接验证的可观察行为；
   工程设施测试（listener/smoke）永远空数组。
5. **生成区红线（Phase 1 起生效）**：API 面只认 shared 契约生成物（suite-hard-rules §4），
   手写 route 只出现在生成器覆盖不到的实现缝。
