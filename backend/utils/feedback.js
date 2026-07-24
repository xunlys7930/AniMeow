/**
 * ===================================================================
 * 反馈池工具函数
 * ===================================================================
 */

const { db } = require('../db');

async function getTableColumns(tableName) {
    const [rows] = await db.query(`SHOW COLUMNS FROM \`${tableName}\``);
    return new Set(rows.map((row) => row.Field));
}

function pickColumn(columns, candidates) {
    return candidates.find((column) => columns.has(column)) || null;
}

async function queryFeedbackRows({ id } = {}) {
    const columns = await getTableColumns('feedback_pool');
    const idColumn = pickColumn(columns, ['id']);
    const contentColumn = pickColumn(columns, ['content', 'feedback', 'message', 'text', 'body']);
    if (!contentColumn) return [];

    const userIdColumn = pickColumn(columns, ['user_id']);
    const usernameColumn = pickColumn(columns, ['username', 'user_name', 'nickname']);
    const statusColumn = pickColumn(columns, ['status']);
    const createdAtColumn = pickColumn(columns, ['created_at', 'create_time', 'created_time', 'updated_at', 'time']);
    const selectFields = [
        idColumn ? `f.\`${idColumn}\` AS id` : '0 AS id',
        `f.\`${contentColumn}\` AS content`,
        statusColumn ? `COALESCE(f.\`${statusColumn}\`, 'open') AS status` : "'open' AS status",
        createdAtColumn
            ? `DATE_FORMAT(f.\`${createdAtColumn}\`, '%Y-%m-%d %H:%i:%s') AS created_at`
            : `DATE_FORMAT(CURRENT_TIMESTAMP, '%Y-%m-%d %H:%i:%s') AS created_at`,
        userIdColumn
            ? "COALESCE(u.username, '匿名用户') AS username"
            : usernameColumn
                ? `COALESCE(f.\`${usernameColumn}\`, '匿名用户') AS username`
                : "'匿名用户' AS username",
    ];

    const params = [];
    let sql = `SELECT ${selectFields.join(', ')} FROM feedback_pool f`;
    if (userIdColumn) {
        sql += ` LEFT JOIN users u ON u.id = f.\`${userIdColumn}\``;
    }
    if (id !== undefined && idColumn) {
        sql += ` WHERE f.\`${idColumn}\` = ?`;
        params.push(id);
    }
    if (createdAtColumn) {
        sql += ` ORDER BY f.\`${createdAtColumn}\` DESC`;
    } else if (idColumn) {
        sql += ` ORDER BY f.\`${idColumn}\` DESC`;
    }
    sql += id === undefined ? ' LIMIT 100' : ' LIMIT 1';

    const [rows] = await db.query(sql, params);
    return rows;
}

async function insertFeedbackRow({ user, content }) {
    const columns = await getTableColumns('feedback_pool');
    const contentColumn = pickColumn(columns, ['content', 'feedback', 'message', 'text', 'body']);
    if (!contentColumn) {
        throw new Error('feedback_pool 表缺少 content 字段');
    }

    const insertColumns = [`\`${contentColumn}\``];
    const placeholders = ['?'];
    const params = [content];
    const userIdColumn = pickColumn(columns, ['user_id']);
    const usernameColumn = pickColumn(columns, ['username', 'user_name', 'nickname']);
    const statusColumn = pickColumn(columns, ['status']);
    const createdAtColumn = pickColumn(columns, ['created_at', 'create_time', 'created_time', 'updated_at', 'time']);

    if (userIdColumn) {
        insertColumns.push(`\`${userIdColumn}\``);
        placeholders.push('?');
        params.push(user.id);
    } else if (usernameColumn) {
        insertColumns.push(`\`${usernameColumn}\``);
        placeholders.push('?');
        params.push(user.username);
    }
    if (statusColumn) {
        insertColumns.push(`\`${statusColumn}\``);
        placeholders.push('?');
        params.push('open');
    }
    if (createdAtColumn) {
        insertColumns.push(`\`${createdAtColumn}\``);
        placeholders.push('CURRENT_TIMESTAMP');
    }

    const [result] = await db.query(
        `INSERT INTO feedback_pool (${insertColumns.join(', ')}) VALUES (${placeholders.join(', ')})`,
        params
    );
    return result.insertId;
}

module.exports = {
    queryFeedbackRows,
    insertFeedbackRow,
};
