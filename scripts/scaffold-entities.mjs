// scripts/scaffold-entities.mjs — 反向工程从真库生成 Exposed Table 对象（DB-First, ADR-0046）
//
// 生成区 = src/main/kotlin/saas/identity/entity/ 整树，禁手改；先删后写。
// 列映射以 spike 编译实证为准（ADR-0046 映射表）：datetime 包 = v1.datetime（无 .kotlin 段）；
// jsonb = jsonb<JsonElement>(名, Json)。
//
// 用法（DATABASE_URL 显式传入，禁兜底）：
//   DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:54321/saas_dev' \
//     node scripts/scaffold-entities.mjs
//
// 前提：本机 PG 已起（pg_ctl … -o "-p 54321" start）；saas_dev 由 shared 仓 migrate-db 管理。

import { createRequire } from "node:module";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { rmSync, mkdirSync, writeFileSync } from "node:fs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");

// 借 pg driver（kotlin 仓无 node_modules；走 sibling shared 仓——springboot 同款招）
const requireFromShared = createRequire(
  resolve(ROOT, "../saas-identity-platform-shared/package.json"),
);
const { Client } = requireFromShared("pg");

// CLAUDE.md §2 禁 env 默认值兜底：连接串含 secret，缺 env 即 fail-fast
const DATABASE_URL = process.env.DATABASE_URL;
if (!DATABASE_URL) {
  console.error("[scaffold-entities] FATAL: DATABASE_URL 未设（禁字面量兜底）");
  console.error(
    "[scaffold-entities] fix: DATABASE_URL='postgresql://…@127.0.0.1:54321/saas_dev' bash scripts/scaffold-entities.sh",
  );
  process.exit(2);
}

const OUTPUT_DIR = resolve(ROOT, "src/main/kotlin/saas/identity/entity");
const PKG = "saas.identity.entity";
const DB_NAME = "saas_dev";
const SHARED_REPO = "saas-identity-platform-shared";

// 簿记表不生成（springboot 同名单）
const EXCLUDE_TABLES = new Set([
  "__drizzle_migrations",
  "schema_migrations",
  "ar_internal_metadata",
]);

// Kotlin 硬关键字（属性名撞上时 backtick 转义）
const KOTLIN_KEYWORDS = new Set([
  "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
  "if", "in", "interface", "is", "null", "object", "package", "return",
  "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
  "var", "when", "while",
]);

function snakeToCamel(s) {
  return s.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
}
function snakeToPascal(s) {
  const camel = snakeToCamel(s);
  return camel[0].toUpperCase() + camel.slice(1);
}
function prop(colName) {
  const n = snakeToCamel(colName);
  return KOTLIN_KEYWORDS.has(n) ? `\`${n}\`` : n;
}

// PG udt → Exposed 1.5.1 列 DSL（v1 命名空间；spike 编译实证，ADR-0046）
function mapColumn(c) {
  const q = JSON.stringify(c.column_name);
  switch (c.udt_name) {
    case "uuid": return { dsl: `uuid(${q})`, mod: "core" };
    case "varchar": return { dsl: `varchar(${q}, ${c.character_maximum_length ?? 255})`, mod: "core" };
    case "text": return { dsl: `text(${q})`, mod: "core" };
    case "int2": return { dsl: `short(${q})`, mod: "core" };
    case "int4": return { dsl: `integer(${q})`, mod: "core" };
    case "int8": return { dsl: `long(${q})`, mod: "core" };
    case "bool": return { dsl: `bool(${q})`, mod: "core" };
    case "jsonb": return { dsl: `jsonb<JsonElement>(${q}, Json)`, mod: "json" };
    case "timestamptz": return { dsl: `timestampWithTimeZone(${q})`, mod: "datetime" };
    case "timestamp": return { dsl: `timestamp(${q})`, mod: "datetime" };
    case "date": return { dsl: `date(${q})`, mod: "datetime" };
    // numeric 与 decimal 在 PG 里同 udt（numeric）；精度必随列返回，缺精度=类型异常，fail-loud
    case "numeric":
      if (c.numeric_precision == null || c.numeric_scale == null)
        throw new Error(`numeric 列缺精度元数据: ${c.table_name}.${c.column_name}`);
      return { dsl: `decimal(${q}, ${c.numeric_precision}, ${c.numeric_scale})`, mod: "core" };
    default:
      // PG enum（USER-DEFINED）→ text 直通 + 注释（ADR-0046 后果①：Phase 3 写路径再定约束）
      if (c.data_type === "USER-DEFINED")
        return { dsl: `text(${q})`, mod: "core", note: ` // PG enum ${c.udt_name}` };
      throw new Error(
        `Unsupported PG column type: ${c.data_type}/${c.udt_name} (${c.table_name}.${c.column_name})`,
      );
  }
}

const MOD_IMPORTS = {
  datetime: [
    "org.jetbrains.exposed.v1.datetime.date",
    "org.jetbrains.exposed.v1.datetime.timestamp",
    "org.jetbrains.exposed.v1.datetime.timestampWithTimeZone",
  ],
  json: [
    "kotlinx.serialization.json.Json",
    "kotlinx.serialization.json.JsonElement",
    "org.jetbrains.exposed.v1.json.jsonb",
  ],
};

async function main() {
  const client = new Client({
    connectionString: DATABASE_URL,
    connectionTimeoutMillis: 10000,
  });
  await client.connect();
  console.log(`[scaffold-entities] 连接 ${DATABASE_URL.replace(/:[^:@/]+@/, ":***@")}`);

  const { rows: tables } = await client.query(`
    SELECT table_name FROM information_schema.tables
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
    ORDER BY table_name
  `);
  const filtered = tables.filter((t) => !EXCLUDE_TABLES.has(t.table_name));
  console.log(
    `[scaffold-entities] 发现 ${filtered.length} 张表（排除簿记 ${tables.length - filtered.length}）`,
  );

  // PK（复合主键 → PrimaryKey(a, b)，Exposed 无需 JPA IdClass）
  const { rows: pkRows } = await client.query(`
    SELECT tc.table_name, kcu.column_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    WHERE tc.table_schema = 'public' AND tc.constraint_type = 'PRIMARY KEY'
    ORDER BY tc.table_name, kcu.ordinal_position
  `);
  const pks = new Map();
  for (const r of pkRows) {
    if (!pks.has(r.table_name)) pks.set(r.table_name, []);
    pks.get(r.table_name).push(r.column_name);
  }

  rmSync(OUTPUT_DIR, { recursive: true, force: true });
  mkdirSync(OUTPUT_DIR, { recursive: true });

  for (const tbl of filtered) {
    const { rows: cols } = await client.query(
      `
      SELECT column_name, data_type, udt_name, is_nullable, character_maximum_length,
             numeric_precision, numeric_scale
      FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = $1
      ORDER BY ordinal_position
    `,
      [tbl.table_name],
    );

    const mapped = cols.map((c) => ({ c, m: mapColumn(c) }));
    const mods = new Set(mapped.map((x) => x.m.mod).filter((m) => m !== "core"));
    const imports = ["org.jetbrains.exposed.v1.core.Table"];
    for (const mod of mods) imports.push(...MOD_IMPORTS[mod]);

    const lines = mapped.map(({ c, m }) => {
      const base = `  val ${prop(c.column_name)} = ${m.dsl}`;
      const nullMark = c.is_nullable === "YES" ? ".nullable()" : "";
      return `${base}${nullMark}${m.note ?? ""}`;
    });
    const pkCols = pks.get(tbl.table_name) ?? [];
    if (pkCols.length === 0)
      throw new Error(`表 ${tbl.table_name} 无主键——反推器不生成无 PK 表（fail-loud）`);
    const pkLine = `  override val primaryKey = PrimaryKey(${pkCols.map(prop).join(", ")})`;

    const kt = `// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 ${DB_NAME}，禁手改。
// 字段语义见 ${SHARED_REPO}/src/db/schema.ts ${snakeToCamel(tbl.table_name)}。
package ${PKG}

${imports.sort().map((i) => `import ${i}`).join("\n")}

object ${snakeToPascal(tbl.table_name)}Table : Table("${tbl.table_name}") {
${lines.join("\n")}
${pkLine}
}
`;
    writeFileSync(resolve(OUTPUT_DIR, `${snakeToPascal(tbl.table_name)}Table.kt`), kt);
    console.log(
      `[scaffold-entities]   ${tbl.table_name} → ${snakeToPascal(tbl.table_name)}Table.kt (${cols.length} 列${pkCols.length > 1 ? `, 复合 PK ${pkCols.join("+")}` : ""})`,
    );
  }

  await client.end();
  console.log(`[scaffold-entities] OK — ${filtered.length} 张表 → ${OUTPUT_DIR}`);
}

main().catch((err) => {
  console.error("[scaffold-entities] FATAL:", err.message);
  process.exit(1);
});
