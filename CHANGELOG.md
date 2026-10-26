# 更新日志

格式照 `v<MAJOR>.<MINOR>.<PATCH>-<YYYYMMDD>`（tag 即放行：全量回归绿后打 tag）。

历史版本（tag 底账，明细见 git log）：

- `v0.2.1-20261025`（2026-10-25）— docs(req): REQ-2026-003 收账——状态已完成（v0.2.0-20261025 首航全绿，prod 探针+hash 链闭合）
- `v0.2.0-20261025`（2026-10-25）— feat(docs): REQ-2026-003 swagger 文档面——shared openapi.yaml 派生零漂移 + webjar + 根跳转
- `v0.1.1-20261025`（2026-10-25）— fix(deploy): HEALTHCHECK --spider 发 HEAD 被 ktor /health 405——改 GET 真拉（首航指纹三）
- `v0.1.0-20261025`（2026-10-25）— fix(deploy): COPY 尾斜杠语义——dist 内容摊进 /app，ENTRYPOINT 改 /app/bin/app（CI 指纹二连）
