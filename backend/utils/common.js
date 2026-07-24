/**
 * ===================================================================
 * 通用工具函数
 * ===================================================================
 */

function normalizeUsername(username) {
    return (username || '').toString().trim().toLowerCase();
}

function validateUsername(username) {
    return /^[a-zA-Z0-9_]{3,24}$/.test(username);
}

module.exports = {
    normalizeUsername,
    validateUsername,
};
