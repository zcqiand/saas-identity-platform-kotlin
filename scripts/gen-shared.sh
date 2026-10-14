#!/usr/bin/env bash
# scripts/gen-shared.sh — 从家族 shared 契约生成 API 缝 + DTO（openapi-generator kotlin-server/ktor）
#
# 三层生成铁律（suite 硬规则 §4「API 面只认生成物」）：
#   整棵生成区 → src/main/kotlin/saas/identity/shared/ 一棵树（openapi-generator，本脚本）：
#     api/                          API 缝（fun Route.XxxApi()，不挂载，Phase 1 spec §3.3）
#     models/                       @Serializable DTO（叶名 models=模板自洽：Paths.kt 硬编码
#                                   {{packageName}}.models.*，见 ADR-0045 §2 修订）
#     Paths.kt                      Resources 路径类（api 文件 import 它）
#     infrastructure/ApiKeyAuth.kt  api 文件 import ApiPrincipal
#   实体   → Phase 2 entity DB-First 时另行接入（本批无）
#
# 生成区每次 regen 先删后写——手改会被机械抹掉；禁止任何绕过脚本的手工编辑。
# 生成的 route 缝不挂载进 Application.kt（Phase 1 spec §3.3）：应用对外仍只有 /health。
# 布局事实与依赖清单见 suite 仓 ADR-0045。
#
# 用法：bash scripts/gen-shared.sh   （无 DB 依赖；L4.codegen.idempotent strict 复放本脚本）

set -euo pipefail

# 本仓是 submodule：git rev-parse 返回外层 suite 根，禁用（fastapi 同款坑）
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.."

SHARED_PKG=src/main/kotlin/saas/identity/shared
SHARED_DIR="$(cd ../saas-identity-platform-shared && pwd)"
GEN_TMP=.openapi-tmp/gen
ZONE_ARGS=(-- "$SHARED_PKG")

echo "[gen-shared] step 1/4 — shared 仓 emit:openapi（TypeSpec SSOT 单 emit）"
(cd "$SHARED_DIR" && npm run emit:openapi)

echo "[gen-shared] step 2/4 — openapi-generator kotlin-server/ktor（openapitools.json 钉 7.25.0）"
rm -rf "$GEN_TMP"
npx --yes @openapitools/openapi-generator-cli generate \
  -g kotlin-server \
  --library ktor \
  -i "$SHARED_DIR/generated/openapi/openapi.yaml" \
  -o "$GEN_TMP" \
  --package-name saas.identity.shared \
  --model-package saas.identity.shared.models \
  --api-package saas.identity.shared.api \
  --additional-properties=hideGenerationTimestamp=true \
  --type-mappings "object=JsonElement,kotlin.Any=kotlinx.serialization.json.JsonElement" \
  --import-mappings "UUID=kotlin.uuid.Uuid,JsonElement=kotlinx.serialization.json.JsonElement"

echo "[gen-shared] step 3/4 — 搬运生成区（显式白名单四件，先删后写；ADR-0045 §2 修订版布局）"
GEN_SHARED="$GEN_TMP/src/main/kotlin/saas/identity/shared"
for d in "$GEN_SHARED/api" "$GEN_SHARED/models"; do
  if [ ! -d "$d" ] || [ -z "$(find "$d" -name '*.kt' -print -quit)" ]; then
    echo "[gen-shared] FATAL: 生成产物包目录缺失或为空：$d（布局漂移？对 ADR-0045）" >&2
    exit 1
  fi
done
for f in Paths.kt infrastructure/ApiKeyAuth.kt; do
  if [ ! -f "$GEN_SHARED/$f" ]; then
    echo "[gen-shared] FATAL: 生成产物支撑件缺失：$GEN_SHARED/$f（布局漂移？对 ADR-0045）" >&2
    exit 1
  fi
done
rm -rf "$SHARED_PKG"
mkdir -p "$SHARED_PKG"
cp -r "$GEN_SHARED/api" "$SHARED_PKG/api"
cp -r "$GEN_SHARED/models" "$SHARED_PKG/models"
cp "$GEN_SHARED/Paths.kt" "$SHARED_PKG/Paths.kt"
cp -r "$GEN_SHARED/infrastructure" "$SHARED_PKG/infrastructure"
rm -rf .openapi-tmp

# 漂移 fail-loud（fastapi 同款）：生成区与 git HEAD 不一致即红。untracked 也算——
# 防生成漏 git add 后门禁读旧产物假绿（池项 5.31 教训）。
if ! git diff --exit-code --quiet "${ZONE_ARGS[@]}" 2>/dev/null \
  || [ -n "$(git ls-files --others --exclude-standard -- "$SHARED_PKG")" ]; then
  echo "[gen-shared] FATAL: 生成区与 git HEAD 不一致（含 untracked 新文件）" >&2
  echo "[gen-shared]        处理：确认 shared .tsp 变更合法后 git add 生成区并 commit，再重跑本脚本核绿" >&2
  exit 1
fi
echo "[gen-shared] OK — 生成区已与 HEAD 同步（API 面只认生成物，硬规则 §4）"
