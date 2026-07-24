/**
 * ===================================================================
 * 🐱 追番喵 (ZhuiFanMiao) 云端代理后端服务
 * ===================================================================
 */

const express = require('express');
const path = require('path');
const http = require('http');

// 加载配置（同时加载 .env）
const { JSON_LIMIT, PORT } = require('./config');

// 加载数据库初始化
const { dbReady } = require('./db/init');

// 加载鉴权中间件
const { authenticateToken } = require('./middleware/auth');

const app = express();
app.use(express.json({ limit: JSON_LIMIT }));
app.use(express.static(path.join(__dirname, 'public')));

// 所有的 /api 路由都经过鉴权
app.use('/api', authenticateToken);

// 等待数据库就绪
app.use('/api', async (_req, _res, next) => {
    await dbReady;
    next();
});

// 挂载各路由模块
app.use('/api', require('./routes/auth'));
app.use('/api', require('./routes/backup'));
app.use('/api', require('./routes/feedback'));
app.use('/api', require('./routes/characterGroup'));
app.use('/api', require('./routes/anime'));
app.use('/api', require('./routes/update'));

// ==========================================
// 🔌 服务启动
// ==========================================
http.createServer(app).listen(PORT, () => {
    console.log(`🚀 服务运行在端口: ${PORT}`);
});
