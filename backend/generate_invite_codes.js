#!/usr/bin/env node

/**
 * 追番喵邀请码生成脚本
 *
 * 用法：
 *   node generate_invite_codes.js
 *
 * 生成后会先把同目录旧 txt 中的历史邀请码补入数据库，
 * 再把当前数据库中的邀请码同步写入同目录：
 *   invite_codes.txt
 */

const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '.env') });

const crypto = require('crypto');
const fs = require('fs');
const readline = require('readline');
const mysql = require('mysql2/promise');

const OUTPUT_FILE = path.join(__dirname, 'invite_codes.txt');
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const INVITE_CODE_PATTERN = /\b[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}\b/gi;

const dbConfig = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number.parseInt(process.env.DB_PORT || '3306', 10),
  user: process.env.DB_USER || 'animeow',
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME || 'animeow',
};

function createPrompt() {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  return {
    question(text) {
      return new Promise((resolve) => rl.question(text, resolve));
    },
    close() {
      rl.close();
    },
  };
}

function generateInviteCode() {
  let code = '';
  for (let i = 0; i < 12; i++) {
    code += CODE_ALPHABET[crypto.randomInt(0, CODE_ALPHABET.length)];
  }
  return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8, 12)}`;
}

function parseCount(input) {
  const count = Number.parseInt(input || '1', 10);
  if (Number.isNaN(count) || count < 1 || count > 50) {
    throw new Error('生成数量必须是 1 到 50 之间的整数');
  }
  return count;
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

function formatDateTime(date) {
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

function parseExpiresAt(input) {
  const raw = (input || '').trim();
  if (!raw) return null;

  const normalized = raw.length === 10
    ? `${raw} 23:59:59`
    : raw.replace('T', ' ');
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) {
    throw new Error('过期时间格式无效，请使用 YYYY-MM-DD 或 YYYY-MM-DD HH:mm:ss');
  }
  return formatDateTime(date);
}

async function ensureTables(connection) {
  await connection.execute(`
    CREATE TABLE IF NOT EXISTS users (
      id INT AUTO_INCREMENT PRIMARY KEY,
      username VARCHAR(32) NOT NULL UNIQUE,
      password_hash VARCHAR(255) NOT NULL,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      last_login_at TIMESTAMP NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `);

  await connection.execute(`
    CREATE TABLE IF NOT EXISTS invite_codes (
      id INT AUTO_INCREMENT PRIMARY KEY,
      code VARCHAR(32) NOT NULL UNIQUE,
      note VARCHAR(255),
      is_used TINYINT(1) DEFAULT 0,
      used_by INT NULL,
      used_at TIMESTAMP NULL,
      expires_at TIMESTAMP NULL,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (used_by) REFERENCES users(id) ON DELETE SET NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `);
}

async function insertInviteCode(connection, { note, expiresAt }) {
  for (let retry = 0; retry < 8; retry++) {
    const code = generateInviteCode();
    try {
      await connection.execute(
        'INSERT INTO invite_codes (code, note, expires_at) VALUES (?, ?, ?)',
        [code, note || null, expiresAt]
      );
      return code;
    } catch (error) {
      if (error.code !== 'ER_DUP_ENTRY' || retry === 7) throw error;
    }
  }
  throw new Error('生成邀请码失败，请重试');
}

function formatNullableDate(value) {
  if (!value) return '永不过期';
  if (value instanceof Date) return formatDateTime(value);
  return String(value);
}

function formatUsedAt(value) {
  if (!value) return '-';
  if (value instanceof Date) return formatDateTime(value);
  return String(value);
}

function normalizeTxtCell(value) {
  const text = (value || '').trim();
  if (!text || text === '-') return null;
  return text;
}

function parseTxtDateTime(value) {
  const text = normalizeTxtCell(value);
  if (!text || text === '永不过期') return null;

  const normalized = text.length === 10
    ? `${text} 23:59:59`
    : text.replace('T', ' ');
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return null;
  return formatDateTime(date);
}

function parseHistoricalInviteCodesFromTxt() {
  if (!fs.existsSync(OUTPUT_FILE)) return [];

  const content = fs.readFileSync(OUTPUT_FILE, 'utf8');
  const records = new Map();

  for (const line of content.split(/\r?\n/)) {
    const matches = line.matchAll(INVITE_CODE_PATTERN);

    for (const match of matches) {
      const code = match[0].toUpperCase();
      const cells = line.split('\t').map((cell) => cell.trim());
      const codeIndex = cells.findIndex((cell) => cell.toUpperCase() === code);
      const isStructuredRow = codeIndex > 0 && cells.length >= codeIndex + 5;
      const record = {
        code,
        note: '从 invite_codes.txt 同步',
        isUsed: 0,
        expiresAt: null,
        usedAt: null,
        isStructuredRow,
      };

      if (isStructuredRow) {
        record.note = normalizeTxtCell(cells[codeIndex + 4]) || record.note;
        record.isUsed = cells[0] === '已使用' ? 1 : 0;
        record.expiresAt = parseTxtDateTime(cells[codeIndex + 1]);
        record.usedAt = parseTxtDateTime(cells[codeIndex + 3]);
      }

      const existing = records.get(code);
      if (!existing || (record.isStructuredRow && !existing.isStructuredRow)) {
        records.set(code, record);
      }
    }
  }

  return [...records.values()];
}

async function importHistoricalInviteCodesFromTxt(connection) {
  const records = parseHistoricalInviteCodesFromTxt();
  let importedCount = 0;

  for (const record of records) {
    const [result] = await connection.execute(
      `
        INSERT IGNORE INTO invite_codes
          (code, note, is_used, used_at, expires_at)
        VALUES (?, ?, ?, ?, ?)
      `,
      [
        record.code,
        record.note,
        record.isUsed,
        record.usedAt,
        record.expiresAt,
      ]
    );

    importedCount += result.affectedRows;
  }

  return {
    scannedCount: records.length,
    importedCount,
  };
}

async function syncInviteCodesTxt(connection, generatedCodes) {
  const historicalSync = await importHistoricalInviteCodesFromTxt(connection);
  const [rows] = await connection.execute(`
    SELECT
      ic.code,
      ic.note,
      ic.is_used,
      ic.created_at,
      ic.expires_at,
      ic.used_at,
      u.username AS used_by_username
    FROM invite_codes ic
    LEFT JOIN users u ON u.id = ic.used_by
    ORDER BY ic.created_at DESC, ic.id DESC
  `);

  const lines = [];
  lines.push('追番喵邀请码列表');
  lines.push(`同步时间：${formatDateTime(new Date())}`);
  lines.push('');

  if (generatedCodes.length > 0) {
    lines.push('本次生成：');
    for (const code of generatedCodes) {
      lines.push(`  ${code}`);
    }
    lines.push('');
  }

  lines.push('全部邀请码：');
  lines.push('状态\t邀请码\t过期时间\t使用者\t使用时间\t备注');
  for (const row of rows) {
    const status = row.is_used ? '已使用' : '未使用';
    const expiresAt = formatNullableDate(row.expires_at);
    const usedBy = row.used_by_username || '-';
    const usedAt = formatUsedAt(row.used_at);
    const note = row.note || '-';
    lines.push(`${status}\t${row.code}\t${expiresAt}\t${usedBy}\t${usedAt}\t${note}`);
  }

  fs.writeFileSync(OUTPUT_FILE, `${lines.join('\n')}\n`, 'utf8');

  return {
    ...historicalSync,
    totalCount: rows.length,
  };
}

async function main() {
  if (!dbConfig.password) {
    throw new Error('缺少 DB_PASSWORD，请先检查后端目录下的 .env');
  }

  const prompt = createPrompt();
  let connection;

  try {
    console.log('=== 追番喵邀请码生成工具 ===');
    console.log('留空过期时间表示永不过期。');
    console.log('');

    const count = parseCount(await prompt.question('生成数量（1-50，默认 1）：'));
    const note = (await prompt.question('备注（可留空，例如 QQ群用户）：')).trim();
    const expiresAt = parseExpiresAt(
      await prompt.question('过期时间（可留空，格式 YYYY-MM-DD 或 YYYY-MM-DD HH:mm:ss）：')
    );

    connection = await mysql.createConnection(dbConfig);
    await ensureTables(connection);

    const generatedCodes = [];
    for (let i = 0; i < count; i++) {
      generatedCodes.push(await insertInviteCode(connection, { note, expiresAt }));
    }

    const syncResult = await syncInviteCodesTxt(connection, generatedCodes);

    console.log('');
    console.log('生成成功：');
    for (const code of generatedCodes) {
      console.log(`  ${code}`);
    }
    console.log('');
    if (syncResult.scannedCount > 0) {
      console.log(
        `历史邀请码同步：扫描 ${syncResult.scannedCount} 个，新增 ${syncResult.importedCount} 个。`
      );
    }
    console.log(`当前 txt 共 ${syncResult.totalCount} 个邀请码。`);
    console.log(`已同步到：${OUTPUT_FILE}`);
  } finally {
    prompt.close();
    if (connection) await connection.end();
  }
}

main().catch((error) => {
  console.error('');
  console.error(`生成失败：${error.message}`);
  process.exit(1);
});
