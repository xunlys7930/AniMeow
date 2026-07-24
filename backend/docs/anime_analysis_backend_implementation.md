# AI 看番风格分析后端实现参考

本仓库根目录的 `backend/` 已集中存放后端文件，入口为 `backend/app.js`。下面是服务器侧实现参考。不要把真实 DeepSeek Key 写进代码仓库，把它放进服务器环境变量 `DEEPSEEK_API_KEY`。

## 环境变量

```bash
DEEPSEEK_API_KEY=你的服务器私有 Key
DEEPSEEK_BASE_URL=https://api.deepseek.com
ANIME_ANALYSIS_TZ=Asia/Shanghai
```

## MySQL 表

如果后端使用 MySQL/MariaDB，可新增一张表保存每日分析记录。这里用 `(user_id, analysis_date)` 保证同一用户同一天只有一条记录；失败后可覆盖重试，成功后当天直接拒绝再次生成。

```sql
CREATE TABLE IF NOT EXISTS anime_analysis_records (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  analysis_date DATE NOT NULL,
  model VARCHAR(64) NOT NULL DEFAULT 'deepseek-v4-flash',
  client_version VARCHAR(32) NULL,
  stats_json LONGTEXT NOT NULL,
  analysis MEDIUMTEXT NULL,
  status ENUM('pending', 'success', 'failed') NOT NULL DEFAULT 'pending',
  error_message TEXT NULL,
  attempts_count INT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uniq_anime_analysis_user_day (user_id, analysis_date),
  KEY idx_anime_analysis_user_created (user_id, created_at)
);
```

## Express 路由

假设你的后端已有：

- `app`：Express 实例
- `pool`：`mysql2/promise` 连接池
- `requireCloudUser`：云账号 JWT/token 鉴权中间件，鉴权后提供 `req.user.id`

如果你的鉴权中间件名字不同，把 `requireCloudUser` 换成现有的登录鉴权即可。

```js
const DEEPSEEK_BASE_URL = process.env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com';
const DEEPSEEK_MODEL = 'deepseek-v4-flash';
const ANALYSIS_TZ = process.env.ANIME_ANALYSIS_TZ || 'Asia/Shanghai';
const MAX_STATS_BYTES = 60 * 1024;

app.post('/api/user/anime-analysis', requireCloudUser, async (req, res) => {
  const userId = req.user.id;
  const model = DEEPSEEK_MODEL;
  const analysisDate = todayInTimeZone(ANALYSIS_TZ);
  const stats = req.body?.stats;
  const clientVersion = `${req.body?.client_version || ''}`.slice(0, 32);

  if (!process.env.DEEPSEEK_API_KEY) {
    return res.status(500).json({
      status: 'error',
      message: '服务器尚未配置 DeepSeek Key',
    });
  }
  if (!stats || typeof stats !== 'object' || Array.isArray(stats)) {
    return res.status(400).json({
      status: 'error',
      message: '缺少有效的统计摘要',
    });
  }

  const statsJson = JSON.stringify(stats);
  if (Buffer.byteLength(statsJson, 'utf8') > MAX_STATS_BYTES) {
    return res.status(400).json({
      status: 'error',
      message: '统计摘要过大，请精简后再试',
    });
  }

  const lockName = `anime-analysis:${userId}:${analysisDate}`;
  const locked = await acquireMysqlLock(lockName, 5);
  if (!locked) {
    return res.status(409).json({
      status: 'error',
      message: '今天的分析正在处理中，请稍后再看',
    });
  }

  let recordId;
  try {
    const [existingRows] = await pool.execute(
      `SELECT id, status, updated_at
       FROM anime_analysis_records
       WHERE user_id = ? AND analysis_date = ?
       LIMIT 1`,
      [userId, analysisDate],
    );

    const existing = existingRows[0];
    if (existing?.status === 'success') {
      return res.status(429).json({
        status: 'error',
        message: '今天已经生成过分析啦，明天再来会更有新鲜感。',
      });
    }

    if (existing) {
      recordId = existing.id;
      await pool.execute(
        `UPDATE anime_analysis_records
         SET status = 'pending',
             model = ?,
             client_version = ?,
             stats_json = ?,
             analysis = NULL,
             error_message = NULL,
             attempts_count = attempts_count + 1
         WHERE id = ?`,
        [model, clientVersion || null, statsJson, recordId],
      );
    } else {
      const [insertResult] = await pool.execute(
        `INSERT INTO anime_analysis_records
          (user_id, analysis_date, model, client_version, stats_json, status)
         VALUES (?, ?, ?, ?, ?, 'pending')`,
        [userId, analysisDate, model, clientVersion || null, statsJson],
      );
      recordId = insertResult.insertId;
    }

    const analysis = await callDeepSeekForAnimeAnalysis(stats, model);

    await pool.execute(
      `UPDATE anime_analysis_records
       SET status = 'success',
           analysis = ?,
           error_message = NULL
       WHERE id = ?`,
      [analysis, recordId],
    );

    return res.json({
      status: 'success',
      data: {
        id: recordId,
        model,
        analysis,
        created_at: new Date().toISOString(),
        remaining_today: 0,
      },
    });
  } catch (error) {
    if (recordId) {
      await pool.execute(
        `UPDATE anime_analysis_records
         SET status = 'failed',
             error_message = ?
         WHERE id = ?`,
        [String(error.message || error).slice(0, 2000), recordId],
      );
    }

    return res.status(502).json({
      status: 'error',
      message: 'AI 分析暂时失败了，请稍后再试。',
    });
  } finally {
    await releaseMysqlLock(lockName);
  }
});

async function callDeepSeekForAnimeAnalysis(stats, model) {
  const response = await fetch(`${DEEPSEEK_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      Authorization: `Bearer ${process.env.DEEPSEEK_API_KEY}`,
    },
    body: JSON.stringify({
      model,
      temperature: 0.85,
      messages: [
        {
          role: 'system',
          content:
            '你是追番喵的可爱看番风格分析助手。请用温柔、可爱但不幼稚的中文分析用户的看番习惯，不要输出心理诊断或过度推断隐私。',
        },
        {
          role: 'user',
          content: buildAnimeAnalysisPrompt(stats),
        },
      ],
    }),
  });

  const raw = await response.text();
  let json;
  try {
    json = JSON.parse(raw);
  } catch {
    throw new Error(`DeepSeek returned non-json: ${raw.slice(0, 200)}`);
  }

  if (!response.ok) {
    const message = json?.error?.message || json?.message || response.statusText;
    throw new Error(`DeepSeek failed: ${message}`);
  }

  const content = json?.choices?.[0]?.message?.content;
  if (!content || typeof content !== 'string') {
    throw new Error('DeepSeek returned empty analysis');
  }
  return content.trim();
}

function buildAnimeAnalysisPrompt(stats) {
  return [
    '请根据下面的追番统计摘要，分析我的看番习惯和风格。',
    '',
    '要求：',
    '1. 用中文输出，语气可爱、温柔，但不要幼稚。',
    '2. 重点分析偏好的题材/标签、观看完成度、评分倾向、追番活跃度和可能的口味画像。',
    '3. 给 3 条轻量建议，比如下一步可以补什么类型、如何整理片单。',
    '4. 不要做心理诊断，不要过度推断隐私。',
    '5. 不要原样复述 JSON，只输出分析结果。',
    '',
    '追番统计摘要：',
    JSON.stringify(stats, null, 2),
  ].join('\n');
}

function todayInTimeZone(timeZone) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

async function acquireMysqlLock(lockName, timeoutSeconds) {
  const [rows] = await pool.execute('SELECT GET_LOCK(?, ?) AS locked', [
    lockName,
    timeoutSeconds,
  ]);
  return rows[0]?.locked === 1;
}

async function releaseMysqlLock(lockName) {
  await pool.execute('SELECT RELEASE_LOCK(?)', [lockName]);
}
```

## 接入检查

1. 服务器安装 Node 18+，或给旧 Node 补 `fetch` polyfill。
2. 设置 `DEEPSEEK_API_KEY` 环境变量，真实 key 不要写进 `app.js`。
3. 执行上面的 MySQL 建表 SQL。
4. 把路由挂到已有 Express 后端，并使用现有云账号鉴权中间件。
5. 用已登录账号请求 `POST /api/user/anime-analysis`，第二次同日请求应返回 `429`。
