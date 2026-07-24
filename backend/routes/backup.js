/**
 * ===================================================================
 * 用户备份路由
 * ===================================================================
 */

const express = require('express');
const router = express.Router();
const { db } = require('../db');
const { authenticateUserToken } = require('../middleware/auth');
const { formatDurationZh } = require('../utils/deepseek');
const { USER_BACKUP_MAX_BYTES, USER_BACKUP_KEEP_COUNT, USER_BACKUP_COOLDOWN_SECONDS } = require('../config');

// 获取备份列表
router.get('/user/backups', authenticateUserToken, async (req, res) => {
    try {
        const [rows] = await db.query(
            `SELECT id, file_name, payload_size,
                    DATE_FORMAT(upload_date, '%Y-%m-%d') AS upload_date,
                    DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
             FROM user_backups
             WHERE user_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT ${USER_BACKUP_KEEP_COUNT}`,
            [req.user.id]
        );
        res.send({ status: 'success', data: rows });
    } catch (error) {
        console.error('读取云端备份失败:', error);
        res.status(500).send({ status: 'error', message: '读取云端备份失败' });
    }
});

// 上传备份
router.post('/user/backups', authenticateUserToken, async (req, res) => {
    const fileName = (req.body.file_name || `AniMeow_${Date.now()}.zip`).toString().slice(0, 255);
    let backupBase64 = (req.body.backup_base64 || '').toString();
    backupBase64 = backupBase64.replace(/^data:.*;base64,/, '');

    if (!backupBase64) {
        return res.status(400).send({ status: 'error', message: 'backup_base64 required' });
    }

    let payload;
    try {
        payload = Buffer.from(backupBase64, 'base64');
    } catch (_) {
        return res.status(400).send({ status: 'error', message: '备份数据格式无效' });
    }
    if (payload.length === 0 || payload.length > USER_BACKUP_MAX_BYTES) {
        return res.status(400).send({
            status: 'error',
            message: `备份大小需在 1-${Math.floor(USER_BACKUP_MAX_BYTES / 1024 / 1024)}MB 内`,
        });
    }

    try {
        const [latestRows] = await db.query(
            `SELECT TIMESTAMPDIFF(SECOND, created_at, NOW()) AS elapsed_seconds
             FROM user_backups
             WHERE user_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT 1`,
            [req.user.id]
        );
        const elapsedSeconds = Number(latestRows[0]?.elapsed_seconds);
        if (
            latestRows.length > 0 &&
            USER_BACKUP_COOLDOWN_SECONDS > 0 &&
            Number.isFinite(elapsedSeconds) &&
            elapsedSeconds < USER_BACKUP_COOLDOWN_SECONDS
        ) {
            const retryAfterSeconds = USER_BACKUP_COOLDOWN_SECONDS - elapsedSeconds;
            return res.status(429).send({
                status: 'error',
                message: `距离上次云存储未满 ${formatDurationZh(USER_BACKUP_COOLDOWN_SECONDS)}，请 ${formatDurationZh(retryAfterSeconds)} 后再试`,
                retry_after_seconds: retryAfterSeconds,
            });
        }

        const [result] = await db.query(
            `INSERT INTO user_backups (user_id, file_name, payload, payload_size, upload_date)
             VALUES (?, ?, ?, ?, CURDATE())`,
            [req.user.id, fileName, payload, payload.length]
        );

        await db.query(
            `DELETE FROM user_backups
             WHERE user_id = ?
               AND id NOT IN (
                 SELECT id FROM (
                   SELECT id
                   FROM user_backups
                   WHERE user_id = ?
                   ORDER BY created_at DESC, id DESC
                   LIMIT ${USER_BACKUP_KEEP_COUNT}
                 ) latest
               )`,
            [req.user.id, req.user.id]
        );

        const [rows] = await db.query(
            `SELECT id, file_name, payload_size,
                    DATE_FORMAT(upload_date, '%Y-%m-%d') AS upload_date,
                    DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
             FROM user_backups
             WHERE id = ?`,
            [result.insertId]
        );
        res.send({ status: 'success', data: rows[0] });
    } catch (error) {
        console.error('上传备份失败:', error);
        res.status(500).send({ status: 'error', message: '上传备份失败' });
    }
});

// 下载备份
router.get('/user/backups/:id/download', authenticateUserToken, async (req, res) => {
    try {
        const [rows] = await db.query(
            'SELECT file_name, payload FROM user_backups WHERE id = ? AND user_id = ? LIMIT 1',
            [req.params.id, req.user.id]
        );
        if (rows.length === 0) {
            return res.status(404).send({ status: 'error', message: '备份不存在' });
        }
        res.setHeader('Content-Type', 'application/zip');
        res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(rows[0].file_name)}"`);
        res.send(rows[0].payload);
    } catch (error) {
        res.status(500).send({ status: 'error', message: '下载备份失败' });
    }
});

module.exports = router;
