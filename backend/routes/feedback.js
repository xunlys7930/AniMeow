/**
 * ===================================================================
 * 反馈池路由
 * ===================================================================
 */

const express = require('express');
const router = express.Router();
const { authenticateUserToken } = require('../middleware/auth');
const { queryFeedbackRows, insertFeedbackRow } = require('../utils/feedback');

// 获取反馈列表
router.get('/feedback', authenticateUserToken, async (_req, res) => {
    try {
        const rows = await queryFeedbackRows();
        res.send({ status: 'success', data: rows });
    } catch (error) {
        console.error('读取反馈池失败:', error);
        res.status(500).send({ status: 'error', message: '读取反馈池失败' });
    }
});

// 提交反馈
router.post('/feedback', authenticateUserToken, async (req, res) => {
    const content = (req.body.content || '').toString().trim();
    if (content.length < 5 || content.length > 1000) {
        return res.status(400).send({ status: 'error', message: '反馈内容需为 5-1000 字' });
    }
    try {
        const insertId = await insertFeedbackRow({ user: req.user, content });
        const rows = await queryFeedbackRows({ id: insertId });
        res.send({
            status: 'success',
            data: rows[0] || {
                id: insertId,
                username: req.user.username,
                content,
                status: 'open',
                created_at: new Date().toLocaleString('sv-SE', { hour12: false }),
            },
        });
    } catch (error) {
        console.error('提交反馈失败:', error);
        res.status(500).send({ status: 'error', message: '提交反馈失败' });
    }
});

module.exports = router;
