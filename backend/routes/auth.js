/**
 * ===================================================================
 * 认证与用户路由
 * ===================================================================
 */

const express = require('express');
const router = express.Router();
const { pool, db } = require('../db');
const { authenticateUserToken, signUserToken } = require('../middleware/auth');
const { hashPassword, verifyPassword } = require('../utils/password');
const { generateInviteCode } = require('../utils/invite');
const { normalizeUsername, validateUsername } = require('../utils/common');
const {
    callDeepSeekForAnimeAnalysis,
    todayInTimeZone,
    ANIME_ANALYSIS_TZ,
    ANIME_ANALYSIS_MAX_STATS_BYTES,
} = require('../utils/deepseek');

// 管理员生成邀请码（使用全局 API_TOKEN）
router.post('/admin/invite-codes', async (req, res) => {
    const count = Math.min(Math.max(parseInt(req.body.count || '1', 10), 1), 50);
    const note = req.body.note ? String(req.body.note).slice(0, 255) : null;
    const expiresAt = req.body.expires_at ? new Date(req.body.expires_at) : null;
    if (expiresAt && Number.isNaN(expiresAt.getTime())) {
        return res.status(400).send({ status: 'error', message: 'expires_at 格式无效' });
    }

    const codes = [];
    try {
        for (let i = 0; i < count; i++) {
            for (let retry = 0; retry < 5; retry++) {
                const code = generateInviteCode();
                try {
                    await db.query(
                        'INSERT INTO invite_codes (code, note, expires_at) VALUES (?, ?, ?)',
                        [code, note, expiresAt ? expiresAt : null]
                    );
                    codes.push(code);
                    break;
                } catch (err) {
                    if (err.code !== 'ER_DUP_ENTRY' || retry === 4) throw err;
                }
            }
        }
        res.send({ status: 'success', data: codes });
    } catch (error) {
        console.error('生成邀请码失败:', error);
        res.status(500).send({ status: 'error', message: '生成邀请码失败' });
    }
});

router.get('/admin/invite-codes', async (_req, res) => {
    try {
        const [rows] = await db.query(
            `SELECT ic.id, ic.code, ic.note, ic.is_used, ic.used_at, ic.expires_at, ic.created_at,
                    u.username AS used_by_username
             FROM invite_codes ic
             LEFT JOIN users u ON u.id = ic.used_by
             ORDER BY ic.created_at DESC
             LIMIT 200`
        );
        res.send({ status: 'success', data: rows });
    } catch (error) {
        res.status(500).send({ status: 'error', message: '读取邀请码失败' });
    }
});

// 用户注册
router.post('/auth/register', async (req, res) => {
    const username = normalizeUsername(req.body.username);
    const password = (req.body.password || '').toString();
    const inviteCode = (req.body.invite_code || '').toString().trim().toUpperCase();

    if (!validateUsername(username)) {
        return res.status(400).send({ status: 'error', message: '用户名需为 3-24 位字母、数字或下划线' });
    }
    if (password.length < 6 || password.length > 72) {
        return res.status(400).send({ status: 'error', message: '密码需为 6-72 位' });
    }
    if (!inviteCode) {
        return res.status(400).send({ status: 'error', message: '请填写邀请码' });
    }

    const conn = await pool.promise().getConnection();
    try {
        await conn.beginTransaction();
        const [inviteRows] = await conn.query(
            `SELECT * FROM invite_codes
             WHERE code = ?
               AND is_used = 0
               AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
             FOR UPDATE`,
            [inviteCode]
        );
        if (inviteRows.length === 0) {
            await conn.rollback();
            return res.status(400).send({ status: 'error', message: '邀请码无效或已被使用' });
        }

        const [existingUsers] = await conn.query('SELECT id FROM users WHERE username = ? LIMIT 1', [username]);
        if (existingUsers.length > 0) {
            await conn.rollback();
            return res.status(409).send({ status: 'error', message: '用户名已存在' });
        }

        const passwordHash = hashPassword(password);
        const [insertResult] = await conn.query(
            'INSERT INTO users (username, password_hash) VALUES (?, ?)',
            [username, passwordHash]
        );
        const userId = insertResult.insertId;

        await conn.query(
            'UPDATE invite_codes SET is_used = 1, used_by = ?, used_at = CURRENT_TIMESTAMP WHERE id = ?',
            [userId, inviteRows[0].id]
        );
        await conn.commit();

        const user = { id: userId, username };
        res.send({ status: 'success', data: { user, token: signUserToken(user) } });
    } catch (error) {
        await conn.rollback();
        if (error.code === 'ER_DUP_ENTRY') {
            return res.status(409).send({ status: 'error', message: '用户名已存在' });
        }
        console.error('注册失败:', error);
        res.status(500).send({ status: 'error', message: '注册失败' });
    } finally {
        conn.release();
    }
});

// 用户登录
router.post('/auth/login', async (req, res) => {
    const username = normalizeUsername(req.body.username);
    const password = (req.body.password || '').toString();
    if (!username || !password) {
        return res.status(400).send({ status: 'error', message: '请输入用户名和密码' });
    }

    try {
        const [rows] = await db.query('SELECT id, username, password_hash FROM users WHERE username = ? LIMIT 1', [
            username,
        ]);
        if (rows.length === 0 || !verifyPassword(password, rows[0].password_hash)) {
            return res.status(401).send({ status: 'error', message: '用户名或密码错误' });
        }
        await db.query('UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?', [rows[0].id]);
        const user = { id: rows[0].id, username: rows[0].username };
        res.send({ status: 'success', data: { user, token: signUserToken(user) } });
    } catch (error) {
        console.error('登录失败:', error);
        res.status(500).send({ status: 'error', message: '登录失败' });
    }
});

// 重置密码
router.post('/auth/reset-password', async (req, res) => {
    const username = normalizeUsername(req.body.username);
    const password = (req.body.password || '').toString();
    const inviteCode = (req.body.invite_code || '').toString().trim().toUpperCase();

    if (!validateUsername(username)) {
        return res.status(400).send({ status: 'error', message: '用户名需为 3-24 位字母、数字或下划线' });
    }
    if (password.length < 6 || password.length > 72) {
        return res.status(400).send({ status: 'error', message: '新密码需为 6-72 位' });
    }
    if (!inviteCode) {
        return res.status(400).send({ status: 'error', message: '请填写邀请码' });
    }

    try {
        const [rows] = await db.query(
            `SELECT u.id, u.username
             FROM users u
             JOIN invite_codes ic ON ic.used_by = u.id
             WHERE u.username = ? AND ic.code = ?
             LIMIT 1`,
            [username, inviteCode]
        );
        if (rows.length === 0) {
            return res.status(400).send({ status: 'error', message: '账号或邀请码不匹配' });
        }

        await db.query(
            'UPDATE users SET password_hash = ? WHERE id = ?',
            [hashPassword(password), rows[0].id]
        );
        res.send({ status: 'success', message: '密码已重置' });
    } catch (error) {
        console.error('重置密码失败:', error);
        res.status(500).send({ status: 'error', message: '重置密码失败' });
    }
});

// 获取当前用户信息
router.get('/user/me', authenticateUserToken, async (req, res) => {
    res.send({ status: 'success', data: { user: req.user } });
});

// AI 看番风格分析
router.post('/user/anime-analysis', authenticateUserToken, async (req, res) => {
    if (!process.env.DEEPSEEK_API_KEY) {
        return res.status(500).send({
            status: 'error',
            message: '服务器尚未配置 DeepSeek Key，暂时不能生成分析。',
        });
    }

    const stats = req.body?.stats;
    if (!stats || typeof stats !== 'object' || Array.isArray(stats)) {
        return res.status(400).send({ status: 'error', message: '缺少有效的追番统计摘要。' });
    }

    let statsJson;
    try {
        statsJson = JSON.stringify(stats);
    } catch (_) {
        return res.status(400).send({ status: 'error', message: '追番统计摘要格式不正确。' });
    }

    if (Buffer.byteLength(statsJson, 'utf8') > ANIME_ANALYSIS_MAX_STATS_BYTES) {
        return res.status(400).send({
            status: 'error',
            message: '追番统计摘要有点太大啦，请精简后再试。',
        });
    }

    const analysisDate = todayInTimeZone(ANIME_ANALYSIS_TZ);
    const clientVersion = (req.body?.client_version || '').toString().trim().slice(0, 32) || null;
    const lockName = `anime-analysis:${req.user.id}:${analysisDate}`;
    let connection;
    let lockAcquired = false;
    let recordId = null;

    try {
        connection = await db.getConnection();
        const [lockRows] = await connection.query('SELECT GET_LOCK(?, 5) AS locked', [lockName]);
        lockAcquired = Number(lockRows[0]?.locked) === 1;
        if (!lockAcquired) {
            return res.status(409).send({
                status: 'error',
                message: '今天的分析正在生成中，请稍后再来看。',
            });
        }

        const [existingRows] = await connection.query(
            `SELECT id, status
             FROM anime_analysis_records
             WHERE user_id = ? AND analysis_date = ?
             LIMIT 1`,
            [req.user.id, analysisDate]
        );
        const existing = existingRows[0];

        if (existing?.status === 'success') {
            return res.status(429).send({
                status: 'error',
                message: '今天已经生成过分析啦，明天再来会更有新鲜感。',
            });
        }

        if (existing) {
            recordId = existing.id;
            await connection.query(
                `UPDATE anime_analysis_records
                 SET status = 'pending',
                     model = ?,
                     client_version = ?,
                     stats_json = ?,
                     analysis = NULL,
                     error_message = NULL,
                     attempts_count = COALESCE(attempts_count, 0) + 1,
                     updated_at = NOW()
                 WHERE id = ?`,
                [require('../config').DEEPSEEK_MODEL, clientVersion, statsJson, recordId]
            );
        } else {
            const [insertResult] = await connection.query(
                `INSERT INTO anime_analysis_records
                   (user_id, analysis_date, model, client_version, stats_json, status, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, 'pending', NOW(), NOW())`,
                [req.user.id, analysisDate, require('../config').DEEPSEEK_MODEL, clientVersion, statsJson]
            );
            recordId = insertResult.insertId;
        }

        const analysis = await callDeepSeekForAnimeAnalysis(stats);
        const createdAt = new Date().toISOString();
        await connection.query(
            `UPDATE anime_analysis_records
             SET status = 'success',
                 analysis = ?,
                 error_message = NULL,
                 updated_at = NOW()
             WHERE id = ?`,
            [analysis, recordId]
        );

        return res.send({
            status: 'success',
            data: {
                id: recordId,
                model: require('../config').DEEPSEEK_MODEL,
                analysis,
                created_at: createdAt,
                remaining_today: 0,
            },
        });
    } catch (error) {
        console.error('AI 看番风格分析失败:', error);
        if (connection && recordId) {
            try {
                await connection.query(
                    `UPDATE anime_analysis_records
                     SET status = 'failed',
                         error_message = ?,
                         updated_at = NOW()
                     WHERE id = ?`,
                    [(error.message || String(error)).slice(0, 2000), recordId]
                );
            } catch (updateError) {
                console.error('记录 AI 分析失败状态失败:', updateError);
            }
        }
        return res.status(502).send({
            status: 'error',
            message: 'AI 分析暂时失败了，请稍后再试。',
        });
    } finally {
        if (connection) {
            if (lockAcquired) {
                try {
                    await connection.query('SELECT RELEASE_LOCK(?)', [lockName]);
                } catch (releaseError) {
                    console.warn('释放 AI 分析锁失败:', releaseError.message);
                }
            }
            connection.release();
        }
    }
});

module.exports = router;
