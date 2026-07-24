/**
 * ===================================================================
 * 密码工具
 * ===================================================================
 */

const crypto = require('crypto');
const { PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH } = require('../config');

function hashPassword(password) {
    const salt = crypto.randomBytes(16).toString('hex');
    const hash = crypto.pbkdf2Sync(password, salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH, 'sha256').toString('hex');
    return `pbkdf2_sha256$${PASSWORD_ITERATIONS}$${salt}$${hash}`;
}

function verifyPassword(password, storedHash) {
    const [algo, iterations, salt, expectedHash] = (storedHash || '').split('$');
    if (algo !== 'pbkdf2_sha256' || !iterations || !salt || !expectedHash) return false;
    const actualHash = crypto
        .pbkdf2Sync(password, salt, Number(iterations), Buffer.from(expectedHash, 'hex').length, 'sha256')
        .toString('hex');
    const actualBuffer = Buffer.from(actualHash, 'hex');
    const expectedBuffer = Buffer.from(expectedHash, 'hex');
    return actualBuffer.length === expectedBuffer.length && crypto.timingSafeEqual(actualBuffer, expectedBuffer);
}

module.exports = { hashPassword, verifyPassword };
