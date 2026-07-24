/**
 * 🐱 追番喵 (AniMeow) 服务器自动补全封面脚本
 *
 * 功能：
 * 1. 每分钟检查一次数据库中 cover_url 为空的数据。
 * 2. 根据 api_id 从 Bangumi API 获取最新封面。
 * 3. 自动更新数据库。
 *
 * 与主服务共用 .env（DB_HOST / DB_USER / DB_PASSWORD / DB_NAME）。
 *
 * 安装依赖（在后端目录）：
 *   npm install
 *
 * 运行：
 *   npm run sync-covers
 */

const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '.env') });
const mysql = require('mysql2/promise');
const axios = require('axios');
const cron = require('node-cron');

if (!process.env.DB_PASSWORD) {
    console.error('❌ 缺少 DB_PASSWORD。请在后端目录准备 .env 并填好数据库配置。');
    process.exit(1);
}

const dbConfig = {
    host: process.env.DB_HOST || '127.0.0.1',
    port: Number.parseInt(process.env.DB_PORT || '3306', 10),
    user: process.env.DB_USER || 'animeow',
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME || 'animeow',
};

const BANGUMI_API_HOST = 'https://api.bgm.tv';
const USER_AGENT = 'AniMeow-Server-Sync-Bot/1.0 (+https://github.com/xunlys7930/AniMeow)';

async function syncMissingCovers() {
    let connection;
    try {
        connection = await mysql.createConnection(dbConfig);

        const [rows] = await connection.execute(
            'SELECT id, api_id, name_cn FROM animes WHERE (cover_url IS NULL OR cover_url = "") AND api_id != 0 LIMIT 5',
        );

        if (rows.length === 0) {
            return;
        }

        console.log(`[${new Date().toLocaleString()}] 发现 ${rows.length} 条缺失封面的数据，准备同步...`);

        for (const anime of rows) {
            try {
                console.log(`正在获取: ${anime.name_cn} (ID: ${anime.api_id})...`);

                const response = await axios.get(`${BANGUMI_API_HOST}/v0/subjects/${anime.api_id}`, {
                    headers: { 'User-Agent': USER_AGENT },
                });

                const images = response.data.images;
                const newCoverUrl = images ? (images.large || images.common || images.medium) : null;

                if (newCoverUrl) {
                    await connection.execute('UPDATE animes SET cover_url = ? WHERE id = ?', [newCoverUrl, anime.id]);
                    console.log(`✅ 成功更新封面: ${anime.name_cn}`);
                } else {
                    console.log(`⚠️ 未能获取到图片: ${anime.name_cn}`);
                }

                await new Promise((resolve) => setTimeout(resolve, 1000));
            } catch (err) {
                console.error(`❌ 获取 ${anime.name_cn} 失败:`, err.message);
            }
        }
    } catch (error) {
        console.error('🔴 数据库连接失败:', error);
    } finally {
        if (connection) await connection.end();
    }
}

cron.schedule('* * * * *', () => {
    syncMissingCovers();
});

console.log('🚀 封面自动同步脚本已启动 (每分钟运行一次)...');

syncMissingCovers();
