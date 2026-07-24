/**
 * ===================================================================
 * 版本更新与下载统计路由
 * ===================================================================
 */

const express = require('express');
const router = express.Router();
const { db } = require('../db');
const {
    getCurrentReleaseInfo,
    buildTrackedDownloadUrl,
    ensureVersionDownloadRow,
    ensureDownloadStatsTable,
    getVersionDownloadCount,
    recordVersionDownload,
} = require('../utils/version');

// 检查更新 (鉴权已豁免)
router.get('/check-update', async (req, res) => {
    const release = getCurrentReleaseInfo();
    let downloadCount = 0;

    try {
        await ensureVersionDownloadRow(release);
        downloadCount = await getVersionDownloadCount(release);
    } catch (error) {
        console.warn('⚠️  下载统计读取失败，检查更新接口继续返回：', error.message);
    }

    res.send({
        status: 'success',
        data: {
            versionCode: release.versionCode,
            versionName: release.versionName,
            updateLog: release.updateLog,
            isForceUpdate: release.isForceUpdate,
            downloadUrl: buildTrackedDownloadUrl(req),
            downloadCount,
        },
    });
});

// 下载入口：先记录下载次数，再跳转到真实安装包地址
router.get('/download/latest', async (req, res) => {
    const release = getCurrentReleaseInfo();

    if (!release.downloadUrl) {
        return res.status(404).send({ status: 'error', message: '未配置下载地址' });
    }

    try {
        await recordVersionDownload(release);
    } catch (error) {
        console.error('❌ 下载统计写入失败：', error);
    }

    res.redirect(302, release.downloadUrl);
});

// 下载统计：需要全局 API_TOKEN
router.get('/admin/download-stats', async (_req, res) => {
    try {
        await ensureDownloadStatsTable();
        const [rows] = await db.query(`
            SELECT
              version_code AS versionCode,
              version_name AS versionName,
              download_count AS downloadCount,
              download_url AS downloadUrl,
              DATE_FORMAT(last_download_at, '%Y-%m-%d %H:%i:%s') AS lastDownloadAt,
              DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,
              DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt
            FROM app_version_downloads
            ORDER BY version_code DESC, updated_at DESC
        `);

        res.send({ status: 'success', data: rows });
    } catch (error) {
        console.error('❌ 下载统计查询失败：', error);
        res.status(500).send({ status: 'error', message: '下载统计查询失败', details: error.message });
    }
});

router.get('/admin/download-stats/latest', async (_req, res) => {
    const release = getCurrentReleaseInfo();

    try {
        await ensureVersionDownloadRow(release);
        const [rows] = await db.query(
            `
            SELECT
              version_code AS versionCode,
              version_name AS versionName,
              download_count AS downloadCount,
              download_url AS downloadUrl,
              DATE_FORMAT(last_download_at, '%Y-%m-%d %H:%i:%s') AS lastDownloadAt,
              DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,
              DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt
            FROM app_version_downloads
            WHERE version_name = ?
            LIMIT 1
            `,
            [release.versionName]
        );

        res.send({ status: 'success', data: rows[0] || null });
    } catch (error) {
        console.error('❌ 最新版本下载统计查询失败：', error);
        res.status(500).send({ status: 'error', message: '最新版本下载统计查询失败', details: error.message });
    }
});

module.exports = router;
