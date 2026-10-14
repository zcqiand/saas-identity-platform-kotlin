#!/usr/bin/env bash
# scripts/scaffold-entities.sh — 真库反推 Exposed Table（DB-First, ADR-0046）
#
# 生成区 = src/main/kotlin/saas/identity/entity/ 整树（禁手改）；先删后写。
# DATABASE_URL 必填显式传入（hard-rules §1 禁兜底）；生成只对 _dev 库。
#
# 用法：DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:54321/saas_dev' \
#         bash scripts/scaffold-entities.sh
# 前提：本机 PG 已起且带 -p 54321（conf 默认 5432 与家族契约不符）

set -euo pipefail

# 本仓是 submodule：git rev-parse 返回外层 suite 根，禁用（springboot/fastapi 同款坑）
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${DATABASE_URL:?FATAL: DATABASE_URL 未设（hard-rules §1 禁兜底）；export DATABASE_URL='postgresql://…@127.0.0.1:54321/saas_dev'}"

ZONE=src/main/kotlin/saas/identity/entity

echo "[scaffold-entities] step 1/2 — node scripts/scaffold-entities.mjs"
node scripts/scaffold-entities.mjs

echo "[scaffold-entities] step 2/2 — 漂移护栏（tracked diff ∪ untracked）"
if ! git diff --exit-code --quiet -- "$ZONE" 2>/dev/null \
  || [ -n "$(git ls-files --others --exclude-standard -- "$ZONE")" ]; then
  echo "[scaffold-entities] FATAL: 生成区与 git HEAD 不一致（含 untracked 新文件）" >&2
  echo "[scaffold-entities]        处理：确认库 schema 变更合法后 git add 生成区并 commit，再重跑本脚本核绿" >&2
  exit 1
fi
echo "[scaffold-entities] OK — 生成区已与 HEAD 同步（DB-First，ADR-0046）"
