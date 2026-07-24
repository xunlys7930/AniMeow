/**
 * ===================================================================
 * 角色群组社区路由
 * ===================================================================
 */

const express = require('express');
const router = express.Router();
const { db } = require('../db');
const { authenticateUserToken } = require('../middleware/auth');
const {
    generateCharacterGroupCommunityId,
    generateCharacterGroupShareCode,
    textValue,
    objectValue,
    boolValue,
    normalizeCharacterGroupPackage,
    characterGroupSummary,
    characterGroupPayload,
    fetchCharacterGroupPackage,
} = require('../utils/characterGroup');

// 获取角色群组列表
router.get('/community/character-groups', async (req, res) => {
    const keyword = textValue(req.query.keyword || req.query.query);
    const limit = Math.min(Math.max(parseInt(req.query.limit || '40', 10), 1), 100);
    const offset = Math.max(parseInt(req.query.offset || '0', 10), 0);
    const params = [];
    let whereSql = 'is_public = 1';
    if (keyword) {
        whereSql += ' AND (name LIKE ? OR COALESCE(description, \'\') LIKE ?)';
        params.push('%' + keyword + '%', '%' + keyword + '%');
    }

    try {
        const [rows] = await db.query(
            'SELECT id, community_id, share_code, user_id, username, name, description, cover_url, ' +
                'character_count, work_count, is_public, download_count, ' +
                "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at, " +
                "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at " +
                'FROM character_group_packages WHERE ' + whereSql +
                ' ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?',
            [...params, limit, offset]
        );
        res.send({ status: 'success', data: { items: rows.map(characterGroupSummary), limit, offset } });
    } catch (error) {
        console.error('读取角色群组社区失败:', error);
        res.status(500).send({ status: 'error', message: '读取角色群组社区失败' });
    }
});

// 创建角色群组
router.post('/community/character-groups', authenticateUserToken, async (req, res) => {
    const rawPayload = objectValue(req.body.payload) || objectValue(req.body);
    const normalized = normalizeCharacterGroupPackage(rawPayload, req.body || {});
    if (normalized.error) {
        return res.status(400).send({ status: 'error', message: normalized.error });
    }
    const isPublic = boolValue(req.body.is_public, true) ? 1 : 0;
    for (let attempt = 0; attempt < 6; attempt++) {
        const communityId = generateCharacterGroupCommunityId();
        const shareCode = generateCharacterGroupShareCode();
        const payload = normalized.payload;
        payload.group = { ...payload.group, community_id: communityId, share_code: shareCode, source: 'community' };
        try {
            const [result] = await db.query(
                'INSERT INTO character_group_packages ' +
                    '(community_id, share_code, user_id, username, name, description, cover_url, ' +
                    'payload_json, character_count, work_count, is_public, download_count, created_at, updated_at) ' +
                    'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW())',
                [
                    communityId,
                    shareCode,
                    req.user.id,
                    req.user.username,
                    normalized.name,
                    normalized.description,
                    normalized.coverUrl,
                    JSON.stringify(payload),
                    normalized.characterCount,
                    normalized.workCount,
                    isPublic,
                ]
            );
            const nowText = new Date().toLocaleString('sv-SE', { hour12: false });
            const row = {
                id: result.insertId,
                community_id: communityId,
                share_code: shareCode,
                user_id: req.user.id,
                username: req.user.username,
                name: normalized.name,
                description: normalized.description,
                cover_url: normalized.coverUrl,
                character_count: normalized.characterCount,
                work_count: normalized.workCount,
                is_public: isPublic,
                download_count: 0,
                created_at: nowText,
                updated_at: nowText,
            };
            return res.send({ status: 'success', data: characterGroupSummary(row) });
        } catch (error) {
            if (error.code === 'ER_DUP_ENTRY' && attempt < 5) continue;
            console.error('上传角色群组失败:', error);
            return res.status(500).send({ status: 'error', message: '上传角色群组失败' });
        }
    }
    res.status(500).send({ status: 'error', message: '生成分享码失败，请稍后再试' });
});

// 更新角色群组
router.put('/community/character-groups/:id', authenticateUserToken, async (req, res) => {
    const id = textValue(req.params.id);
    if (!id) return res.status(400).send({ status: 'error', message: '缺少群组 ID' });

    const [rows] = await db.query(
        'SELECT * FROM character_group_packages WHERE community_id = ? OR id = ? LIMIT 1',
        [id, isNaN(id) ? 0 : Number(id)]
    );
    if (rows.length === 0) {
        return res.status(404).send({ status: 'error', message: '角色群组不存在' });
    }
    const existing = rows[0];

    if (Number(existing.user_id) !== req.user.id) {
        return res.status(403).send({ status: 'error', message: '只能更新自己分享的群组' });
    }

    const rawPayload = objectValue(req.body.payload) || objectValue(req.body);
    const normalized = normalizeCharacterGroupPackage(rawPayload, req.body || {});
    if (normalized.error) {
        return res.status(400).send({ status: 'error', message: normalized.error });
    }

    const isPublic = boolValue(req.body.is_public, existing.is_public === 1) ? 1 : 0;
    const payload = normalized.payload;
    payload.group = { ...payload.group, community_id: existing.community_id, share_code: existing.share_code, source: 'community' };

    try {
        await db.query(
            'UPDATE character_group_packages SET ' +
                'name = ?, description = ?, cover_url = ?, payload_json = ?, ' +
                'character_count = ?, work_count = ?, is_public = ?, updated_at = NOW() ' +
                'WHERE id = ?',
            [
                normalized.name,
                normalized.description,
                normalized.coverUrl,
                JSON.stringify(payload),
                normalized.characterCount,
                normalized.workCount,
                isPublic,
                existing.id,
            ]
        );
        const nowText = new Date().toLocaleString('sv-SE', { hour12: false });
        const row = {
            ...existing,
            name: normalized.name,
            description: normalized.description,
            cover_url: normalized.coverUrl,
            character_count: normalized.characterCount,
            work_count: normalized.workCount,
            is_public: isPublic,
            updated_at: nowText,
        };
        return res.send({ status: 'success', data: characterGroupSummary(row) });
    } catch (error) {
        console.error('更新角色群组失败:', error);
        return res.status(500).send({ status: 'error', message: '更新角色群组失败' });
    }
});

// 删除角色群组
router.delete('/community/character-groups/:id', authenticateUserToken, async (req, res) => {
    const id = textValue(req.params.id);
    if (!id) return res.status(400).send({ status: 'error', message: '缺少群组 ID' });

    const [rows] = await db.query(
        'SELECT * FROM character_group_packages WHERE community_id = ? OR id = ? LIMIT 1',
        [id, isNaN(id) ? 0 : Number(id)]
    );
    if (rows.length === 0) {
        return res.status(404).send({ status: 'error', message: '角色群组不存在' });
    }
    const existing = rows[0];

    if (Number(existing.user_id) !== req.user.id) {
        return res.status(403).send({ status: 'error', message: '只能删除自己分享的群组' });
    }

    try {
        await db.query('DELETE FROM character_group_packages WHERE id = ?', [existing.id]);
        return res.send({ status: 'success', message: '已删除分享的群组' });
    } catch (error) {
        console.error('删除角色群组失败:', error);
        return res.status(500).send({ status: 'error', message: '删除角色群组失败' });
    }
});

// 通过分享码获取角色群组
router.get('/community/character-groups/share/:code', async (req, res) => {
    const code = textValue(req.params.code)?.toUpperCase();
    if (!code) return res.status(400).send({ status: 'error', message: '缺少分享码' });
    try {
        const row = await fetchCharacterGroupPackage('share_code = ?', [code]);
        if (!row) return res.status(404).send({ status: 'error', message: '分享码不存在' });
        res.send({ status: 'success', data: { ...characterGroupSummary(row), payload: characterGroupPayload(row) } });
    } catch (error) {
        console.error('分享码读取角色群组失败:', error);
        res.status(500).send({ status: 'error', message: '分享码读取角色群组失败' });
    }
});

// 通过 ID 获取角色群组详情
router.get('/community/character-groups/:id', async (req, res) => {
    const id = textValue(req.params.id);
    if (!id) return res.status(400).send({ status: 'error', message: '缺少群组 ID' });
    const isNumericId = /^\d+$/.test(id);
    const whereSql = isNumericId ? 'is_public = 1 AND (community_id = ? OR id = ?)' : 'is_public = 1 AND community_id = ?';
    const params = isNumericId ? [id, Number(id)] : [id];
    try {
        const row = await fetchCharacterGroupPackage(whereSql, params);
        if (!row) return res.status(404).send({ status: 'error', message: '角色群组不存在或未公开' });
        res.send({ status: 'success', data: { ...characterGroupSummary(row), payload: characterGroupPayload(row) } });
    } catch (error) {
        console.error('读取角色群组详情失败:', error);
        res.status(500).send({ status: 'error', message: '读取角色群组详情失败' });
    }
});

module.exports = router;
