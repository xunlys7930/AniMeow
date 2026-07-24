/**
 * ===================================================================
 * 鉴权中间件
 * ===================================================================
 */

const { API_TOKEN, USER_TOKEN_SECRET, USER_TOKEN_TTL_SECONDS } = require('../config');
const crypto = require('crypto');

function extractBearerToken(authHeader) {
    if (typeof authHeader !== 'string') return null;
    const match = authHeader.match(/^Bearer\s+(.+)$/i);
    return match ? match[1].trim() : null;
}

function secureStringEquals(actual, expected) {
    if (!actual || !expected) return false;
    const actualBuffer = Buffer.from(actual);
    const expectedBuffer = Buffer.from(expected);
    return actualBuffer.length === expectedBuffer.length && crypto.timingSafeEqual(actualBuffer, expectedBuffer);
}

// 全局 API Token 鉴权（用于管理员接口和旧版接口）
const authenticateToken = (req, res, next) => {
    const routePath = req.originalUrl.split('?')[0];

    // 允许"检查更新"和账号注册/登录接口无需全局 API Token 访问。
    // 注意：这里使用 originalUrl 匹配，避免挂载点导致的路径偏移
    if (
        routePath === '/api/check-update' ||
        routePath.startsWith('/api/download/') ||
        routePath === '/api/auth/register' ||
        routePath === '/api/auth/login' ||
        routePath === '/api/auth/reset-password' ||
        routePath.startsWith('/api/user/') ||
        routePath.startsWith('/api/community/') ||
        routePath === '/api/feedback' ||
        routePath.startsWith('/api/feedback/')
    ) {
        return next();
    }

    const token = extractBearerToken(req.headers['authorization']);

    if (!secureStringEquals(token, API_TOKEN)) {
        console.warn(`⚠️  [鉴权拦截] 来源IP: ${req.ip}, 路径: ${req.originalUrl}`);
        return res.status(401).send({ status: 'error', message: 'Unauthorized: Access Denied' });
    }
    next();
};

// 用户 Token 工具函数
function base64UrlEncode(input) {
    return Buffer.from(input).toString('base64url');
}

function signUserToken(user) {
    const header = base64UrlEncode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const now = Math.floor(Date.now() / 1000);
    const payload = base64UrlEncode(
        JSON.stringify({
            sub: user.id,
            username: user.username,
            iat: now,
            exp: now + USER_TOKEN_TTL_SECONDS,
        })
    );
    const body = `${header}.${payload}`;
    const signature = crypto.createHmac('sha256', USER_TOKEN_SECRET).update(body).digest('base64url');
    return `${body}.${signature}`;
}

function verifyUserToken(token) {
    if (!token || typeof token !== 'string') return null;
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const [header, payload, signature] = parts;
    const body = `${header}.${payload}`;
    const expected = crypto.createHmac('sha256', USER_TOKEN_SECRET).update(body).digest('base64url');
    if (!secureStringEquals(signature, expected)) return null;
    try {
        const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
        if (!decoded.exp || decoded.exp < Math.floor(Date.now() / 1000)) return null;
        if (!Number.isInteger(Number(decoded.sub)) || Number(decoded.sub) <= 0) return null;
        if (typeof decoded.username !== 'string' || decoded.username.length === 0) return null;
        return decoded;
    } catch (_) {
        return null;
    }
}

// 用户 Token 鉴权中间件
function authenticateUserToken(req, res, next) {
    const token = extractBearerToken(req.headers['authorization']);
    const payload = verifyUserToken(token);
    if (!payload) {
        return res.status(401).send({ status: 'error', message: '请先登录账号' });
    }
    req.user = {
        id: Number(payload.sub),
        username: payload.username,
    };
    next();
}

module.exports = {
    authenticateToken,
    authenticateUserToken,
    signUserToken,
    verifyUserToken,
};
