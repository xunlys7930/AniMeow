/**
 * ===================================================================
 * 🐱 追番喵 (AniMeow) 云端代理后端服务
 * ===================================================================
 */

require('dotenv').config();
const express = require('express');
const mysql = require('mysql2');
const axios = require('axios');
const http = require('http');

const app = express();
app.use(express.json());
app.use(express.static('public'));

// 启动前校验必要的环境变量，避免误用未配置的实例
if (!process.env.DB_PASSWORD || !process.env.API_TOKEN) {
    console.error('❌ 缺少必要环境变量（DB_PASSWORD / API_TOKEN）。请复制 .env.example 为 .env 并填好后再启动。');
    process.exit(1);
}

// ==========================================
// 🔐 核心鉴权中间件 (已修复路径识别问题)
// ==========================================
const authenticateToken = (req, res, next) => {
    // 允许"检查更新"接口无需 Token 访问
    // 注意：这里使用 originalUrl 匹配，避免挂载点导致的路径偏移
    if (req.originalUrl.includes('/check-update')) {
        return next();
    }

    const authHeader = req.headers['authorization'];
    const token = authHeader && authHeader.split(' ')[1];

    if (!token || token !== process.env.API_TOKEN) {
        console.warn(`⚠️  [鉴权拦截] 来源IP: ${req.ip}, 路径: ${req.originalUrl}`);
        return res.status(401).send({ status: 'error', message: 'Unauthorized: Access Denied' });
    }
    next();
};

// 所有的 /api 路由都经过鉴权
app.use('/api', authenticateToken);

// ==========================================
// 🗄️ 数据库配置
// ==========================================
const pool = mysql.createPool({
    host: process.env.DB_HOST || '127.0.0.1',
    user: process.env.DB_USER || 'zhuifanmiao',
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME || 'zhuifanmiao',
    waitForConnections: true,
    connectionLimit: 10,
});
const db = pool.promise();

// 初始化表结构
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
    try {
        await db.query(createTableSql);
        console.log('✅ 数据库同步成功');
    } catch (err) {
        console.error('❌ 数据库初始化失败：', err);
    }
}
initDB();

// ==========================================
// 🚀 业务接口 (API)
// ==========================================

// 3.1 Bangumi 抓取
app.get('/api/fetch-bangumi/:bgmId', async (req, res) => {
    const bgmId = req.params.bgmId;
    try {
        const bgmRes = await axios.get(`https://api.bgm.tv/v0/subjects/${bgmId}`, {
            headers: { 'User-Agent': 'AniMeow/1.0 (+https://github.com/xunlys7930/AniMeow)' }
        });
        const data = bgmRes.data;
        let studio = '未知制作公司';
        if (data.infobox) {
            const studioItem = data.infobox.find(item => item.key === '动画制作');
            if (studioItem) studio = Array.isArray(studioItem.value) ? studioItem.value.map(v => v.v).join(', ') : studioItem.value;
        }
        let airDate = data.date || null;
        if (airDate === '') airDate = null;

        const unifiedData = {
            api_id: data.id,
            source: 'bangumi',
            name_cn: data.name_cn || data.name,
            name_original: data.name,
            cover_url: data.images ? data.images.large : '',
            summary: data.summary || '暂无简介',
            air_date: airDate,
            total_eps: data.eps || 0,
            tv_eps: data.eps || 0,
            sp_eps: 0,
            eps_breakdown: `共 ${data.eps || 0} 集`,
            score: data.rating ? data.rating.score : 0.0,
            studio: studio,
            sort_order: 0,
            tags: data.tags ? data.tags.slice(0, 10).map(t => t.name).join(',') : ''
        };

        const insertSql = `INSERT IGNORE INTO animes SET ?`;
        await db.query(insertSql, unifiedData);
        res.send({ status: 'success', data: unifiedData });
    } catch (error) {
        res.status(500).send({ status: 'error', message: error.message });
    }
});

// 3.2 AniList 抓取
app.get('/api/fetch-anilist/:aniId', async (req, res) => {
    const aniId = parseInt(req.params.aniId);
    const query = `query ($id: Int) { Media (id: $id, type: ANIME) { id title { romaji english native } description coverImage { extraLarge } startDate { year month day } episodes format averageScore studios(isMain: true) { nodes { name } } tags { name } } }`;
    try {
        const aniRes = await axios.post('https://graphql.anilist.co', { query, variables: { id: aniId } });
        const media = aniRes.data.data.Media;
        if (!media) return res.status(404).send({ status: 'error', message: 'Not Found' });

        const unifiedData = {
            api_id: media.id,
            source: 'anilist',
            name_cn: media.title.english || media.title.romaji || '未知标题',
            name_original: media.title.native || '',
            cover_url: media.coverImage ? media.coverImage.extraLarge : '',
            summary: (media.description || '').replace(/<[^>]*>?/gm, ''),
            air_date: media.startDate.year ? `${media.startDate.year}-${String(media.startDate.month || 1).padStart(2, '0')}-${String(media.startDate.day || 1).padStart(2, '0')}` : null,
            total_eps: media.episodes || 0,
            tv_eps: media.episodes || 0,
            sp_eps: 0,
            eps_breakdown: `${media.format || 'TV'} 共 ${media.episodes || 0} 集`,
            score: media.averageScore ? (media.averageScore / 10).toFixed(1) : 0.0,
            studio: media.studios.nodes.map(n => n.name).join(', '),
            sort_order: 0,
            tags: media.tags.slice(0, 10).map(t => t.name).join(',')
        };
        await db.query('INSERT IGNORE INTO animes SET ?', unifiedData);
        res.send({ status: 'success', data: unifiedData });
    } catch (error) {
        res.status(500).send({ status: 'error', message: error.message });
    }
});

// 4.1 获取列表
app.get('/api/animes', async (req, res) => {
    try {
        const [rows] = await db.query('SELECT * FROM animes ORDER BY id DESC');
        res.send({ status: 'success', data: rows });
    } catch (error) {
        res.status(500).send({ status: 'error', message: 'DB Error' });
    }
});

// 4.2 云端搜索
app.get('/api/search', async (req, res) => {
    const { keyword, tag, year } = req.query;
    try {
        let sql = 'SELECT * FROM animes';
        const params = [];
        const conditions = [];
        if (keyword) { conditions.push('(name_cn LIKE ? OR name_original LIKE ?)'); params.push(`%${keyword}%`, `%${keyword}%`); }
        if (tag) { conditions.push('tags LIKE ?'); params.push(`%${tag}%`); }
        if (year) { conditions.push('YEAR(air_date) = ?'); params.push(year); }
        if (conditions.length > 0) sql += ' WHERE ' + conditions.join(' AND ');
        sql += ' ORDER BY id DESC';
        const [rows] = await db.query(sql, params);
        res.send({ status: 'success', data: rows });
    } catch (error) {
        res.status(500).send({ status: 'error', message: 'Search Failed' });
    }
});

// 4.3 同步封面 URL（App 端遇到好看封面会主动推送）
app.post('/api/update_cover', async (req, res) => {
    const { title, cover_url } = req.body;

    // 参数校验
    if (!title || !cover_url) {
        return res.status(400).send({
            status: 'error',
            message: 'title and cover_url required'
        });
    }
    if (!cover_url.startsWith('http')) {
        return res.status(400).send({
            status: 'error',
            message: 'cover_url must be a http(s) URL'
        });
    }

    try {
        // 同名作品（中文名 OR 原名匹配）都刷一遍封面
        const [result] = await db.query(
            'UPDATE animes SET cover_url = ?, update_time = CURRENT_TIMESTAMP ' +
            'WHERE name_cn = ? OR name_original = ?',
            [cover_url, title, title]
        );

        if (result.affectedRows > 0) {
            console.log(`🖼️  封面同步: "${title}" → ${result.affectedRows} 行`);
            res.send({ status: 'success', updated: result.affectedRows });
        } else {
            // 资源库里没收录这部作品，不算错误
            console.log(`ℹ️  封面同步：未匹配 "${title}"`);
            res.send({ status: 'no_match', updated: 0 });
        }
    } catch (error) {
        console.error('封面同步出错:', error);
        res.status(500).send({ status: 'error', message: error.message });
    }
});

// 5.1 检查更新 (鉴权已豁免)；版本与下载地址由环境变量配置，避免仓库泄露你的域名/IP
app.get('/api/check-update', (req, res) => {
    let downloadUrls = {};
    const rawUrls = process.env.UPDATE_DOWNLOAD_URLS_JSON;
    if (rawUrls) {
        try {
            downloadUrls = JSON.parse(rawUrls);
            if (typeof downloadUrls !== 'object' || downloadUrls === null) downloadUrls = {};
        } catch (_) {
            downloadUrls = {};
        }
    }
    res.send({
        status: 'success',
        data: {
            versionCode: parseInt(process.env.UPDATE_VERSION_CODE || '0', 10) || 0,
            versionName: process.env.UPDATE_VERSION_NAME || '',
            updateLog: process.env.UPDATE_CHANGELOG || '',
            isForceUpdate: process.env.UPDATE_FORCE === 'true' || process.env.UPDATE_FORCE === '1',
            downloadUrl: process.env.UPDATE_DOWNLOAD_URL || '',
            downloadUrls,
        },
    });
});

// ==========================================
// 🔌 服务启动
// ==========================================
const PORT = process.env.PORT || 3000;
http.createServer(app).listen(PORT, () => {
    console.log(`🚀 服务运行在端口: ${PORT}`);
});
