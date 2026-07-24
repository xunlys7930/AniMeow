/**
 * ===================================================================
 * 版本管理与下载统计工具
 * ===================================================================
 */

const { db } = require('../db');
const { DEFAULT_UPDATE_INFO, parseBooleanEnv, normalizeVersionName } = require('../config');
const { tryEnsureColumn } = require('../db/init');

function getCurrentReleaseInfo() {
    const envVersionCode = Number(process.env.UPDATE_VERSION_CODE);
    const versionCode =
        Number.isInteger(envVersionCode) && envVersionCode > 0
            ? envVersionCode
            : DEFAULT_UPDATE_INFO.versionCode;

    return {
        versionCode,
        versionName: normalizeVersionName(process.env.UPDATE_VERSION_NAME || DEFAULT_UPDATE_INFO.versionName),
        updateLog: process.env.UPDATE_CHANGELOG || DEFAULT_UPDATE_INFO.updateLog,
        isForceUpdate: parseBooleanEnv(process.env.UPDATE_FORCE, DEFAULT_UPDATE_INFO.isForceUpdate),
        downloadUrl: process.env.UPDATE_DOWNLOAD_URL || DEFAULT_UPDATE_INFO.downloadUrl,
    };
}

function getPublicBaseUrl(req) {
    const configuredBaseUrl = process.env.PUBLIC_BASE_URL || process.env.CLOUD_API_BASE;
    if (configuredBaseUrl) {
        return configuredBaseUrl.replace(/\/+$/, '');
    }

    const forwardedProto = req.get('x-forwarded-proto');
    const protocol = forwardedProto ? forwardedProto.split(',')[0].trim() : req.protocol || 'http';
    return `${protocol}://${req.get('host')}`;
}

function buildTrackedDownloadUrl(req) {
    return `${getPublicBaseUrl(req)}/api/download/latest`;
}

async function ensureDownloadStatsTable() {
    const CREATE_VERSION_DOWNLOADS_TABLE_SQL = `
        CREATE TABLE IF NOT EXISTS app_version_downloads (
          id INT AUTO_INCREMENT PRIMARY KEY,
          version_code INT NOT NULL,
          version_name VARCHAR(50) NOT NULL,
          download_url VARCHAR(1000) NOT NULL,
          download_count INT UNSIGNED NOT NULL DEFAULT 0,
          last_download_at DATETIME NULL,
          created_at DATETIME NULL,
          updated_at DATETIME NULL,
          UNIQUE KEY unique_app_version_downloads_version_name (version_name),
          INDEX idx_app_version_downloads_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `;
    await db.query(CREATE_VERSION_DOWNLOADS_TABLE_SQL);
    await tryEnsureColumn('app_version_downloads', 'version_code', 'version_code INT NOT NULL DEFAULT 0');
    await tryEnsureColumn('app_version_downloads', 'version_name', "version_name VARCHAR(50) NOT NULL DEFAULT ''");
    await tryEnsureColumn('app_version_downloads', 'download_url', "download_url VARCHAR(1000) NOT NULL DEFAULT ''");
    await tryEnsureColumn('app_version_downloads', 'download_count', 'download_count INT UNSIGNED NOT NULL DEFAULT 0');
    await tryEnsureColumn('app_version_downloads', 'last_download_at', 'last_download_at DATETIME NULL');
    await tryEnsureColumn('app_version_downloads', 'created_at', 'created_at DATETIME NULL');
    await tryEnsureColumn('app_version_downloads', 'updated_at', 'updated_at DATETIME NULL');
}

async function ensureVersionDownloadRow(release) {
    await tryEnsureColumn('character_group_packages', 'community_id', "community_id VARCHAR(40) NOT NULL DEFAULT ''");
    await tryEnsureColumn('character_group_packages', 'share_code', "share_code VARCHAR(16) NOT NULL DEFAULT ''");
    await tryEnsureColumn('character_group_packages', 'user_id', 'user_id INT NULL');
    await tryEnsureColumn('character_group_packages', 'username', 'username VARCHAR(32) NULL');
    await tryEnsureColumn('character_group_packages', 'name', "name VARCHAR(255) NOT NULL DEFAULT ''");
    await tryEnsureColumn('character_group_packages', 'description', 'description TEXT NULL');
    await tryEnsureColumn('character_group_packages', 'cover_url', 'cover_url VARCHAR(1000) NULL');
    await tryEnsureColumn('character_group_packages', 'payload_json', 'payload_json LONGTEXT NULL');
    await tryEnsureColumn('character_group_packages', 'character_count', 'character_count INT UNSIGNED NOT NULL DEFAULT 0');
    await tryEnsureColumn('character_group_packages', 'work_count', 'work_count INT UNSIGNED NOT NULL DEFAULT 0');
    await tryEnsureColumn('character_group_packages', 'is_public', 'is_public TINYINT(1) NOT NULL DEFAULT 1');
    await tryEnsureColumn('character_group_packages', 'download_count', 'download_count INT UNSIGNED NOT NULL DEFAULT 0');
    await tryEnsureColumn('character_group_packages', 'created_at', 'created_at DATETIME NULL');
    await tryEnsureColumn('character_group_packages', 'updated_at', 'updated_at DATETIME NULL');
    await ensureDownloadStatsTable();
    await db.query(
        `
        INSERT INTO app_version_downloads (version_code, version_name, download_url, download_count, created_at, updated_at)
        VALUES (?, ?, ?, 0, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
          version_code = VALUES(version_code),
          download_url = VALUES(download_url),
          updated_at = NOW()
        `,
        [release.versionCode, release.versionName, release.downloadUrl]
    );
}

async function getVersionDownloadCount(release) {
    await ensureDownloadStatsTable();
    const [rows] = await db.query(
        'SELECT download_count FROM app_version_downloads WHERE version_name = ? LIMIT 1',
        [release.versionName]
    );
    return rows.length > 0 ? Number(rows[0].download_count || 0) : 0;
}

async function recordVersionDownload(release) {
    await ensureDownloadStatsTable();
    await db.query(
        `
        INSERT INTO app_version_downloads (
          version_code, version_name, download_url, download_count,
          last_download_at, created_at, updated_at
        )
        VALUES (?, ?, ?, 1, NOW(), NOW(), NOW())
        ON DUPLICATE KEY UPDATE
          version_code = VALUES(version_code),
          download_url = VALUES(download_url),
          download_count = download_count + 1,
          last_download_at = NOW(),
          updated_at = NOW()
        `,
        [release.versionCode, release.versionName, release.downloadUrl]
    );
}

module.exports = {
    getCurrentReleaseInfo,
    getPublicBaseUrl,
    buildTrackedDownloadUrl,
    ensureDownloadStatsTable,
    ensureVersionDownloadRow,
    getVersionDownloadCount,
    recordVersionDownload,
};
