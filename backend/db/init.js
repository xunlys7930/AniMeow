/**
 * ===================================================================
 * 数据库初始化：建表 + 字段兼容迁移
 * ===================================================================
 */

const { db } = require('./index');

const CREATE_CHARACTER_GROUP_PACKAGES_TABLE_SQL = `
    CREATE TABLE IF NOT EXISTS character_group_packages (
      id INT AUTO_INCREMENT PRIMARY KEY,
      community_id VARCHAR(40) NOT NULL UNIQUE,
      share_code VARCHAR(16) NOT NULL UNIQUE,
      user_id INT NULL,
      username VARCHAR(32) NULL,
      name VARCHAR(255) NOT NULL,
      description TEXT NULL,
      cover_url VARCHAR(1000) NULL,
      payload_json LONGTEXT NOT NULL,
      character_count INT UNSIGNED NOT NULL DEFAULT 0,
      work_count INT UNSIGNED NOT NULL DEFAULT 0,
      is_public TINYINT(1) NOT NULL DEFAULT 1,
      download_count INT UNSIGNED NOT NULL DEFAULT 0,
      created_at DATETIME NULL,
      updated_at DATETIME NULL,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
      INDEX idx_character_groups_public_updated (is_public, updated_at),
      INDEX idx_character_groups_share_code (share_code)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
`;

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

async function ensureColumn(tableName, columnName, columnDefinition) {
    const [rows] = await db.query(`SHOW COLUMNS FROM \`${tableName}\` LIKE ?`, [columnName]);
    if (rows.length > 0) return;
    await db.query(`ALTER TABLE \`${tableName}\` ADD COLUMN ${columnDefinition}`);
}

async function tryEnsureColumn(tableName, columnName, columnDefinition) {
    try {
        await ensureColumn(tableName, columnName, columnDefinition);
    } catch (error) {
        console.warn(`⚠️  表字段兼容迁移跳过：${tableName}.${columnName}`, error.message);
    }
}

async function ensureDownloadStatsTable() {
    await db.query(CREATE_VERSION_DOWNLOADS_TABLE_SQL);
    await tryEnsureColumn('app_version_downloads', 'version_code', 'version_code INT NOT NULL DEFAULT 0');
    await tryEnsureColumn('app_version_downloads', 'version_name', "version_name VARCHAR(50) NOT NULL DEFAULT ''");
    await tryEnsureColumn('app_version_downloads', 'download_url', "download_url VARCHAR(1000) NOT NULL DEFAULT ''");
    await tryEnsureColumn('app_version_downloads', 'download_count', 'download_count INT UNSIGNED NOT NULL DEFAULT 0');
    await tryEnsureColumn('app_version_downloads', 'last_download_at', 'last_download_at DATETIME NULL');
    await tryEnsureColumn('app_version_downloads', 'created_at', 'created_at DATETIME NULL');
    await tryEnsureColumn('app_version_downloads', 'updated_at', 'updated_at DATETIME NULL');
}

async function initDB() {
    const createTableSql = `
    CREATE TABLE IF NOT EXISTS animes (
      id INT AUTO_INCREMENT PRIMARY KEY,
      api_id INT NOT NULL,
      source VARCHAR(20) NOT NULL,
      name_cn VARCHAR(255),
      name_original VARCHAR(255),
      cover_url VARCHAR(500),
      summary TEXT,
      air_date DATE,
      total_eps INT DEFAULT 0,
      tv_eps INT DEFAULT 0,
      sp_eps INT DEFAULT 0,
      eps_breakdown VARCHAR(100),
      score DECIMAL(3,1) DEFAULT 0.0,
      studio VARCHAR(255),
      sort_order INT DEFAULT 0,
      tags VARCHAR(500),
      update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY unique_anime_source (api_id, source)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
    const createUsersSql = `
    CREATE TABLE IF NOT EXISTS users (
      id INT AUTO_INCREMENT PRIMARY KEY,
      username VARCHAR(32) NOT NULL UNIQUE,
      password_hash VARCHAR(255) NOT NULL,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      last_login_at TIMESTAMP NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
    const createInviteCodesSql = `
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
  `;
    const createUserBackupsSql = `
    CREATE TABLE IF NOT EXISTS user_backups (
      id INT AUTO_INCREMENT PRIMARY KEY,
      user_id INT NOT NULL,
      file_name VARCHAR(255) NOT NULL,
      payload LONGBLOB NOT NULL,
      payload_size INT NOT NULL,
      upload_date DATE NOT NULL,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      INDEX idx_user_backups_user_created (user_id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
    const createFeedbackSql = `
    CREATE TABLE IF NOT EXISTS feedback_pool (
      id INT AUTO_INCREMENT PRIMARY KEY,
      user_id INT NOT NULL,
      content TEXT NOT NULL,
      status VARCHAR(20) DEFAULT 'open',
      created_at DATETIME NULL,
      updated_at DATETIME NULL,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      INDEX idx_feedback_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
    const createAnimeAnalysisRecordsSql = `
    CREATE TABLE IF NOT EXISTS anime_analysis_records (
      id INT AUTO_INCREMENT PRIMARY KEY,
      user_id INT NOT NULL,
      analysis_date DATE NOT NULL,
      model VARCHAR(64) NOT NULL DEFAULT 'deepseek-v4-flash',
      client_version VARCHAR(32) NULL,
      stats_json LONGTEXT NOT NULL,
      analysis MEDIUMTEXT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'pending',
      error_message TEXT NULL,
      attempts_count INT UNSIGNED NOT NULL DEFAULT 1,
      created_at DATETIME NULL,
      updated_at DATETIME NULL,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE KEY unique_anime_analysis_user_day (user_id, analysis_date),
      INDEX idx_anime_analysis_user_created (user_id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
    try {
        await db.query(createTableSql);
        await db.query(createUsersSql);
        await db.query(createInviteCodesSql);
        await db.query(createUserBackupsSql);
        await db.query(createFeedbackSql);
        await db.query(createAnimeAnalysisRecordsSql);
        await db.query(CREATE_CHARACTER_GROUP_PACKAGES_TABLE_SQL);
        await db.query(CREATE_VERSION_DOWNLOADS_TABLE_SQL);
        console.log('✅ 数据库同步成功');
    } catch (err) {
        console.error('❌ 数据库初始化失败：', err);
        throw err;
    }

    await tryEnsureColumn('feedback_pool', 'user_id', 'user_id INT NULL');
    await tryEnsureColumn('feedback_pool', 'content', 'content TEXT NULL');
    await tryEnsureColumn('feedback_pool', 'status', "status VARCHAR(20) DEFAULT 'open'");
    await tryEnsureColumn('feedback_pool', 'created_at', 'created_at DATETIME NULL');
    await tryEnsureColumn('anime_analysis_records', 'user_id', 'user_id INT NULL');
    await tryEnsureColumn('anime_analysis_records', 'analysis_date', 'analysis_date DATE NULL');
    await tryEnsureColumn('anime_analysis_records', 'model', "model VARCHAR(64) DEFAULT 'deepseek-v4-flash'");
    await tryEnsureColumn('anime_analysis_records', 'client_version', 'client_version VARCHAR(32) NULL');
    await tryEnsureColumn('anime_analysis_records', 'stats_json', 'stats_json LONGTEXT NULL');
    await tryEnsureColumn('anime_analysis_records', 'analysis', 'analysis MEDIUMTEXT NULL');
    await tryEnsureColumn('anime_analysis_records', 'status', "status VARCHAR(20) DEFAULT 'pending'");
    await tryEnsureColumn('anime_analysis_records', 'error_message', 'error_message TEXT NULL');
    await tryEnsureColumn('anime_analysis_records', 'attempts_count', 'attempts_count INT UNSIGNED NOT NULL DEFAULT 1');
    await tryEnsureColumn('anime_analysis_records', 'created_at', 'created_at DATETIME NULL');
    await tryEnsureColumn('anime_analysis_records', 'updated_at', 'updated_at DATETIME NULL');
    await ensureDownloadStatsTable();
}

const dbReady = initDB().catch((error) => {
    console.error('❌ 数据库初始化异常，服务继续启动，具体接口可能返回数据库错误：', error.message);
});

module.exports = { dbReady, ensureDownloadStatsTable, tryEnsureColumn };
