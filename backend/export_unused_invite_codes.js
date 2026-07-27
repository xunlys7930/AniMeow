#!/usr/bin/env node

/**
 * 追番喵未使用邀请码导出脚本
 *
 * 用法：
 *   node export_unused_invite_codes.js
 *   node export_unused_invite_codes.js ./unused_invite_codes.txt
 *
 * 默认只导出“当前可用”的邀请码：未使用，且未过期。
 * 如需包含已过期但从未被使用的邀请码：
 *   node export_unused_invite_codes.js --include-expired
 */

const fs = require('fs');
const path = require('path');

loadEnv();

const includeExpired = process.argv.includes('--include-expired');
const outputArg = process.argv.find((arg) => !arg.startsWith('--') && arg !== process.argv[0] && arg !== process.argv[1]);
const outputFile = path.resolve(
  outputArg || process.env.UNUSED_INVITE_CODES_OUTPUT || path.join(__dirname, 'unused_invite_codes.txt')
);

const dbConfig = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number.parseInt(process.env.DB_PORT || '3306', 10),
  user: process.env.DB_USER || 'animeow',
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME || 'animeow',
};

function loadEnv() {
  try {
    require('dotenv').config({ path: path.join(__dirname, '.env') });
    return;
  } catch (error) {
    if (error.code !== 'MODULE_NOT_FOUND') throw error;
  }

  const envFile = path.join(__dirname, '.env');
  if (!fs.existsSync(envFile)) return;

  for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;

    const equalsIndex = trimmed.indexOf('=');
    if (equalsIndex <= 0) continue;

    const key = trimmed.slice(0, equalsIndex).trim();
    let value = trimmed.slice(equalsIndex + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (!process.env[key]) process.env[key] = value;
  }
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

function formatDateTime(value) {
  if (!value) return '永不过期';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);

  return [
    date.getFullYear(),
    pad2(date.getMonth() + 1),
    pad2(date.getDate()),
  ].join('-') + ' ' + [
    pad2(date.getHours()),
    pad2(date.getMinutes()),
    pad2(date.getSeconds()),
  ].join(':');
}

function escapeCell(value) {
  return String(value ?? '-').replace(/\r?\n/g, ' ').trim() || '-';
}

function buildTxt(rows) {
  const lines = [];
  lines.push('追番喵未使用邀请码');
  lines.push(`导出时间：${formatDateTime(new Date())}`);
  lines.push(`导出范围：${includeExpired ? '所有未使用邀请码（包含已过期）' : '当前可用邀请码（未使用且未过期）'}`);
  lines.push(`数量：${rows.length}`);
  lines.push('');

  lines.push('邀请码清单：');
  for (const row of rows) {
    lines.push(row.code);
  }
  lines.push('');

  lines.push('详细信息：');
  lines.push('邀请码\t过期时间\t创建时间\t备注');
  for (const row of rows) {
    lines.push([
      escapeCell(row.code),
      escapeCell(formatDateTime(row.expires_at)),
      escapeCell(formatDateTime(row.created_at)),
      escapeCell(row.note),
    ].join('\t'));
  }

  return `${lines.join('\n')}\n`;
}

async function queryUnusedInviteCodes(connection) {
  const where = includeExpired
    ? 'ic.is_used = 0'
    : 'ic.is_used = 0 AND (ic.expires_at IS NULL OR ic.expires_at > CURRENT_TIMESTAMP)';

  const [rows] = await connection.execute(`
    SELECT
      ic.code,
      ic.note,
      ic.expires_at,
      ic.created_at
    FROM invite_codes ic
    WHERE ${where}
    ORDER BY ic.created_at DESC, ic.id DESC
  `);

  return rows;
}

async function main() {
  if (!dbConfig.password) {
    throw new Error('缺少 DB_PASSWORD，请确认后端目录下的 .env 已配置数据库密码');
  }

  const mysql = loadMysql();
  const connection = await mysql.createConnection(dbConfig);
  try {
    const rows = await queryUnusedInviteCodes(connection);
    fs.mkdirSync(path.dirname(outputFile), { recursive: true });
    fs.writeFileSync(outputFile, buildTxt(rows), 'utf8');

    console.log(`已导出 ${rows.length} 个未使用邀请码：${outputFile}`);
    if (rows.length > 0) {
      console.log('');
      for (const row of rows) {
        console.log(row.code);
      }
    }
  } finally {
    await connection.end();
  }
}

function loadMysql() {
  try {
    return require('mysql2/promise');
  } catch (error) {
    if (error.code === 'MODULE_NOT_FOUND') {
      throw new Error('缺少 mysql2 依赖，请先在后端目录执行 npm install mysql2');
    }
    throw error;
  }
}

main().catch((error) => {
  console.error(`导出失败：${error.message}`);
  process.exit(1);
});
