/**
 * ===================================================================
 * 数据库连接池
 * ===================================================================
 */

const mysql = require('mysql2');
const { DB_CONFIG } = require('../config');

const pool = mysql.createPool(DB_CONFIG);
const db = pool.promise();

module.exports = { pool, db };
