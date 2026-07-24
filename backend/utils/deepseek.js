/**
 * ===================================================================
 * DeepSeek AI 分析工具
 * ===================================================================
 */

const axios = require('axios');
const {
    DEEPSEEK_BASE_URL,
    DEEPSEEK_MODEL,
    DEEPSEEK_TIMEOUT_MS,
    ANIME_ANALYSIS_TZ,
    ANIME_ANALYSIS_MAX_STATS_BYTES,
} = require('../config');

function buildAnimeAnalysisPrompt(stats) {
    return [
        '你是一位语气可爱、温柔、观察力很强的二次元看番搭子。请根据下面的追番统计摘要，分析用户的看番习惯和风格。',
        '',
        '要求：',
        '1. 用中文输出，语气可爱但不要幼稚，也不要过度卖萌。',
        '2. 重点分析偏好的题材/标签、观看完成度、评分倾向、追番活跃度和可能的口味画像。',
        '3. 给 3 条轻量建议，比如下一步可以补什么类型、如何整理片单。',
        '4. 不要做心理诊断，不要过度推断隐私，不要提及任何不存在的数据。',
        '5. 不要原样复述 JSON，只输出分析结果。',
        '',
        '追番统计摘要：',
        JSON.stringify(stats, null, 2),
    ].join('\n');
}

function extractAssistantContent(data) {
    const content = data?.choices?.[0]?.message?.content;
    if (typeof content === 'string') return content.trim();
    if (Array.isArray(content)) {
        return content
            .map((part) => {
                if (typeof part === 'string') return part;
                if (part && typeof part.text === 'string') return part.text;
                return '';
            })
            .join('')
            .trim();
    }
    if (typeof data?.output_text === 'string') return data.output_text.trim();
    return '';
}

function deepSeekErrorMessage(data, fallback) {
    return data?.error?.message || data?.message || fallback || 'DeepSeek request failed';
}

async function callDeepSeekForAnimeAnalysis(stats) {
    const response = await axios.post(
        `${DEEPSEEK_BASE_URL}/chat/completions`,
        {
            model: DEEPSEEK_MODEL,
            temperature: 0.85,
            messages: [
                {
                    role: 'system',
                    content:
                        '你是追番喵服务器里的可爱看番风格分析助手。请用温柔、可爱但不幼稚的中文分析用户的看番习惯，不要做心理诊断或过度推断隐私。',
                },
                {
                    role: 'user',
                    content: buildAnimeAnalysisPrompt(stats),
                },
            ],
        },
        {
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                Authorization: `Bearer ${process.env.DEEPSEEK_API_KEY}`,
            },
            timeout: DEEPSEEK_TIMEOUT_MS,
            validateStatus: () => true,
        }
    );

    if (response.status < 200 || response.status >= 300) {
        throw new Error(deepSeekErrorMessage(response.data, response.statusText));
    }

    const analysis = extractAssistantContent(response.data);
    if (!analysis) {
        throw new Error('DeepSeek returned empty analysis');
    }
    return analysis;
}

function todayInTimeZone(timeZone) {
    const parts = new Intl.DateTimeFormat('en-US', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).formatToParts(new Date());
    const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
    return `${values.year}-${values.month}-${values.day}`;
}

function formatDurationZh(totalSeconds) {
    const safeSeconds = Math.max(1, Math.ceil(Number(totalSeconds) || 0));
    const minutes = Math.floor(safeSeconds / 60);
    const seconds = safeSeconds % 60;
    if (minutes <= 0) return `${seconds} 秒`;
    if (seconds === 0) return `${minutes} 分钟`;
    return `${minutes} 分 ${seconds} 秒`;
}

module.exports = {
    callDeepSeekForAnimeAnalysis,
    todayInTimeZone,
    formatDurationZh,
    ANIME_ANALYSIS_TZ,
    ANIME_ANALYSIS_MAX_STATS_BYTES,
};
