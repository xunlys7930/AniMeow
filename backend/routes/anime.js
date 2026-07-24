/**
 * ===================================================================
 * 番剧数据路由（抓取、搜索、封面同步）
 * ===================================================================
 */

const express = require('express');
const router = express.Router();
const axios = require('axios');
const { db } = require('../db');

// Bangumi 抓取
router.get('/fetch-bangumi/:bgmId', async (req, res) => {
    const bgmId = req.params.bgmId;
    try {
        const bgmRes = await axios.get(`https://api.bgm.tv/v0/subjects/${bgmId}`, {
            headers: { 'User-Agent': 'AniMeow/1.3.7 (+https://github.com/xunlys7930/AniMeow)' }
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

// AniList 抓取
router.get('/fetch-anilist/:aniId', async (req, res) => {
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

// 获取番剧列表
router.get('/animes', async (req, res) => {
    try {
        const [rows] = await db.query('SELECT * FROM animes ORDER BY id DESC');
        res.send({ status: 'success', data: rows });
    } catch (error) {
        res.status(500).send({ status: 'error', message: 'DB Error' });
    }
});

// 云端搜索
router.get('/search', async (req, res) => {
    const { keyword, tag, tags, year, month, format, sort } = req.query;
    try {
        let sql = 'SELECT * FROM animes';
        const params = [];
        const conditions = [];
        const tagFilters = uniqueList([...parseList(tag), ...parseList(tags)]);
        if (keyword) { conditions.push('(name_cn LIKE ? OR name_original LIKE ?)'); params.push(`%${keyword}%`, `%${keyword}%`); }
        for (const item of tagFilters) {
            conditions.push('tags LIKE ?');
            params.push(`%${item}%`);
        }
        if (format) {
            conditions.push('(eps_breakdown LIKE ? OR tags LIKE ?)');
            params.push(`%${format}%`, `%${format}%`);
        }
        const numericYear = parseInteger(year);
        const numericMonth = parseInteger(month);
        if (numericYear && numericMonth && [1, 4, 7, 10].includes(numericMonth)) {
            const [start, end] = seasonBounds(numericYear, numericMonth);
            conditions.push('air_date >= ? AND air_date < ?');
            params.push(start, end);
        } else if (numericYear) {
            conditions.push('YEAR(air_date) = ?');
            params.push(numericYear);
        } else if (numericMonth && numericMonth >= 1 && numericMonth <= 12) {
            conditions.push('MONTH(air_date) = ?');
            params.push(numericMonth);
        }
        if (conditions.length > 0) sql += ' WHERE ' + conditions.join(' AND ');
        sql += orderByClause(sort);
        const limit = clampInteger(req.query.limit, 1, 100);
        const offset = Math.max(parseInteger(req.query.offset) || 0, 0);
        if (limit) {
            sql += ' LIMIT ? OFFSET ?';
            params.push(limit, offset);
        }
        const [rows] = await db.query(sql, params);
        res.send({ status: 'success', data: rows });
    } catch (error) {
        res.status(500).send({ status: 'error', message: 'Search Failed' });
    }
});

function parseList(value) {
    if (!value) return [];
    const raw = Array.isArray(value) ? value.join(',') : String(value);
    return raw
        .split(/[,，/、]/)
        .map((item) => item.trim())
        .filter(Boolean)
        .filter((item) => item !== '全部');
}

function uniqueList(items) {
    return [...new Set(items)];
}

function parseInteger(value) {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) ? parsed : null;
}

function clampInteger(value, min, max) {
    const parsed = parseInteger(value);
    if (parsed == null) return null;
    return Math.min(Math.max(parsed, min), max);
}

function seasonBounds(year, month) {
    const start = seasonStart(year, month);
    const end = month === 10
        ? seasonStart(year + 1, 1)
        : seasonStart(year, month === 1 ? 4 : month + 3);
    return [formatDate(start), formatDate(end)];
}

function seasonStart(year, month) {
    if (month === 1) return new Date(year - 1, 11, 25);
    return new Date(year, month - 2, 25);
}

function formatDate(date) {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
}

function orderByClause(sort) {
    switch (sort) {
        case 'score':
            return ' ORDER BY score DESC, air_date DESC, id DESC';
        case 'title':
            return ' ORDER BY name_cn ASC, name_original ASC, id DESC';
        case 'air_date':
        default:
            return ' ORDER BY air_date DESC, id DESC';
    }
}

// 同步封面 URL
router.post('/update_cover', async (req, res) => {
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

module.exports = router;
