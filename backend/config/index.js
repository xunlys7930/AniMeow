/**
 * ===================================================================
 * 追番喵 (ZhuiFanMiao) 配置中心
 * ===================================================================
 */

const path = require('path');
const crypto = require('crypto');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });

function parseIntegerEnv(value, fallback, minValue = 0) {
    const parsed = Number.parseInt(value, 10);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.max(minValue, parsed);
}

function parseBooleanEnv(value, fallback = false) {
    if (value === undefined || value === null || value === '') return fallback;
    return ['1', 'true', 'yes', 'y'].includes(String(value).trim().toLowerCase());
}

function normalizeVersionName(versionName) {
    const value = String(versionName || '').trim();
    return value.startsWith('v') ? value.slice(1) : value;
}

// 数据库配置
const DB_CONFIG = {
    host: process.env.DB_HOST || '127.0.0.1',
    port: parseIntegerEnv(process.env.DB_PORT, 3306, 1),
    user: process.env.DB_USER || 'animeow',
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME || 'animeow',
    waitForConnections: true,
    connectionLimit: 10,
};

// 鉴权配置。生产环境不允许依赖源码中的默认密钥。
const API_TOKEN = String(process.env.API_TOKEN || '').trim();
const configuredUserTokenSecret = String(process.env.USER_TOKEN_SECRET || '').trim();
if (
    process.env.NODE_ENV === 'production' &&
    (!API_TOKEN || !configuredUserTokenSecret || API_TOKEN === configuredUserTokenSecret)
) {
    throw new Error('生产环境必须配置 API_TOKEN 和 USER_TOKEN_SECRET');
}
const USER_TOKEN_SECRET = configuredUserTokenSecret || crypto.randomBytes(32).toString('hex');
if (!configuredUserTokenSecret) {
    console.warn('⚠️  未配置 USER_TOKEN_SECRET：当前使用仅本次进程有效的随机开发密钥');
}
const USER_TOKEN_TTL_SECONDS = Number(process.env.USER_TOKEN_TTL_SECONDS || 60 * 60 * 24 * 30);

// 密码配置
const PASSWORD_ITERATIONS = 120000;
const PASSWORD_KEY_LENGTH = 32;

// 备份配置
const USER_BACKUP_MAX_BYTES = Number(process.env.USER_BACKUP_MAX_BYTES || 20 * 1024 * 1024);
const USER_BACKUP_KEEP_COUNT = parseIntegerEnv(process.env.USER_BACKUP_KEEP_COUNT, 3, 1);
const USER_BACKUP_COOLDOWN_SECONDS = parseIntegerEnv(process.env.USER_BACKUP_COOLDOWN_SECONDS, 300, 0);

// DeepSeek AI 配置
const DEEPSEEK_BASE_URL = (process.env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com').replace(/\/+$/, '');
const DEEPSEEK_MODEL = process.env.DEEPSEEK_MODEL || 'deepseek-v4-flash';
const DEEPSEEK_TIMEOUT_MS = parseIntegerEnv(process.env.DEEPSEEK_TIMEOUT_MS, 60000, 1000);

// 时区与统计配置
const ANIME_ANALYSIS_TZ = process.env.ANIME_ANALYSIS_TZ || 'Asia/Shanghai';
const ANIME_ANALYSIS_MAX_STATS_BYTES = parseIntegerEnv(
    process.env.ANIME_ANALYSIS_MAX_STATS_BYTES,
    60 * 1024,
    1024
);

// 默认版本更新信息
const DEFAULT_UPDATE_INFO = {
    versionCode: 25,
    versionName: '1.3.8',
    updateLog:
        '【首页快捷设置】支持在首页直接调整宫格列数；' +
        '【可选诊断日志】可记录并复制关键操作与错误信息，默认关闭；' +
        '【问题修复】修复了若干 bug。',
    isForceUpdate: false,
    // 自建实例必须通过 UPDATE_DOWNLOAD_URL 配置真实下载地址。
    downloadUrl: '',
};

// 服务端口
const PORT = process.env.PORT || 3000;

// JSON 请求体大小限制
const JSON_LIMIT = process.env.JSON_LIMIT || '32mb';

module.exports = {
    DB_CONFIG,
    API_TOKEN,
    USER_TOKEN_SECRET,
    USER_TOKEN_TTL_SECONDS,
    PASSWORD_ITERATIONS,
    PASSWORD_KEY_LENGTH,
    USER_BACKUP_MAX_BYTES,
    USER_BACKUP_KEEP_COUNT,
    USER_BACKUP_COOLDOWN_SECONDS,
    DEEPSEEK_BASE_URL,
    DEEPSEEK_MODEL,
    DEEPSEEK_TIMEOUT_MS,
    ANIME_ANALYSIS_TZ,
    ANIME_ANALYSIS_MAX_STATS_BYTES,
    DEFAULT_UPDATE_INFO,
    PORT,
    JSON_LIMIT,
    parseIntegerEnv,
    parseBooleanEnv,
    normalizeVersionName,
};
