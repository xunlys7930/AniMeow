/**
 * ===================================================================
 * 角色群组工具函数
 * ===================================================================
 */

const crypto = require('crypto');
const { db } = require('../db');

function generateCharacterGroupCommunityId() {
    return 'cg_' + crypto.randomBytes(8).toString('hex');
}

function generateCharacterGroupShareCode() {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    for (let i = 0; i < 8; i++) {
        code += alphabet[crypto.randomInt(0, alphabet.length)];
    }
    return code;
}

function textValue(value) {
    const text = value === undefined || value === null ? '' : String(value).trim();
    return text.length > 0 ? text : null;
}

function objectValue(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    return value;
}

function arrayValue(value) {
    return Array.isArray(value) ? value : [];
}

function boolValue(value, fallback = false) {
    if (value === undefined || value === null || value === '') return fallback;
    if (typeof value === 'boolean') return value;
    return ['1', 'true', 'yes', 'y', '公开'].includes(String(value).trim().toLowerCase());
}

function normalizeCharacterGroupPackage(rawPayload, body = {}) {
    const sourcePayload = objectValue(rawPayload);
    if (!sourcePayload) return { error: 'payload 必须是对象' };
    const payload = JSON.parse(JSON.stringify(sourcePayload));
    const group = objectValue(payload.group) || {};
    const characters = arrayValue(payload.characters || payload.character_snapshots);
    const works = arrayValue(payload.works || payload.work_snapshots || payload.subjects);
    const name =
        textValue(body.name) ||
        textValue(group.name) ||
        textValue(group.title) ||
        textValue(payload.name) ||
        '未命名角色群组';
    const description = textValue(body.description) || textValue(group.description) || textValue(payload.description);
    const coverUrl = textValue(body.cover_url) || textValue(group.cover_url) || textValue(group.coverUrl);
    if (characters.length < 2 || works.length < 1) {
        return { error: '角色群组至少需要 2 个角色和 1 部作品' };
    }
    payload.schema = textValue(payload.schema) || 'anime_tracker.character_group.v1';
    payload.group = { ...group, name, description, cover_url: coverUrl };
    payload.characters = characters;
    payload.works = works;
    return { payload, name, description, coverUrl, characterCount: characters.length, workCount: works.length };
}

function characterGroupSummary(row) {
    return {
        id: row.community_id || String(row.id),
        numeric_id: row.id,
        community_id: row.community_id,
        share_code: row.share_code,
        name: row.name,
        description: row.description,
        cover_url: row.cover_url,
        username: row.username,
        character_count: Number(row.character_count || 0),
        work_count: Number(row.work_count || 0),
        download_count: Number(row.download_count || 0),
        is_public: Number(row.is_public || 0) === 1,
        created_at: row.created_at,
        updated_at: row.updated_at,
    };
}

function characterGroupPayload(row) {
    let payload;
    try {
        payload = JSON.parse(row.payload_json || '{}');
    } catch (_) {
        payload = {};
    }
    const group = objectValue(payload.group) || {};
    payload.group = {
        ...group,
        name: row.name,
        description: row.description,
        cover_url: row.cover_url,
        community_id: row.community_id,
        share_code: row.share_code,
        source: 'community',
    };
    payload.schema = textValue(payload.schema) || 'anime_tracker.character_group.v1';
    payload.characters = arrayValue(payload.characters || payload.character_snapshots);
    payload.works = arrayValue(payload.works || payload.work_snapshots || payload.subjects);
    return payload;
}

async function fetchCharacterGroupPackage(whereSql, params) {
    const [rows] = await db.query(
        'SELECT * FROM character_group_packages WHERE ' + whereSql + ' LIMIT 1',
        params
    );
    if (rows.length === 0) return null;
    await db.query(
        'UPDATE character_group_packages SET download_count = download_count + 1 WHERE id = ?',
        [rows[0].id]
    );
    rows[0].download_count = Number(rows[0].download_count || 0) + 1;
    return rows[0];
}

module.exports = {
    generateCharacterGroupCommunityId,
    generateCharacterGroupShareCode,
    textValue,
    objectValue,
    arrayValue,
    boolValue,
    normalizeCharacterGroupPackage,
    characterGroupSummary,
    characterGroupPayload,
    fetchCharacterGroupPackage,
};
