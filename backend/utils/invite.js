/**
 * ===================================================================
 * 邀请码工具
 * ===================================================================
 */

const crypto = require('crypto');

function generateInviteCode() {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    for (let i = 0; i < 12; i++) {
        code += alphabet[crypto.randomInt(0, alphabet.length)];
    }
    return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8, 12)}`;
}

module.exports = { generateInviteCode };
