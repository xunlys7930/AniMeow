package com.animeow.app.data.local

/**
 * 用于标签的简繁归一和轻量同义归一。
 * 这里维护常见动画标签用字的简繁映射；如需更完整转换可后续接入 OpenCC。
 */
private val TRADITIONAL_TO_SIMPLIFIED = mapOf(
    '輕' to '轻',
    '愛' to '爱',
    '學' to '学',
    '習' to '习',
    '動' to '动',
    '畫' to '画',
    '聲' to '声',
    '優' to '优',
    '劇' to '剧',
    '場' to '场',
    '戀' to '恋',
    '懸' to '悬',
    '戰' to '战',
    '鬥' to '斗',
    '樂' to '乐',
    '與' to '与',
    '為' to '为',
    '體' to '体',
    '內' to '内',
    '時' to '时',
    '間' to '间',
    '後' to '后',
    '發' to '发',
    '長' to '长',
    '門' to '门',
    '風' to '风',
    '飛' to '飞',
    '馬' to '马',
    '魚' to '鱼',
    '鳥' to '鸟',
    '龍' to '龙',
    '龜' to '龟',
    '點' to '点',
    '對' to '对',
    '應' to '应',
    '當' to '当',
    '從' to '从',
    '來' to '来',
    '這' to '这',
    '個' to '个',
    '們' to '们',
    '國' to '国',
    '開' to '开',
    '關' to '关',
    '見' to '见',
    '認' to '认',
    '識' to '识',
    '語' to '语',
    '讀' to '读',
    '寫' to '写',
    '說' to '说',
    '話' to '话',
    '誰' to '谁',
    '難' to '难',
    '離' to '离',
    '兩' to '两',
    '萬' to '万',
    '係' to '系',
    '繫' to '系',
    '綫' to '线',
    '線' to '线',
    '葉' to '叶',
    '華' to '华',
    '紅' to '红',
    '綠' to '绿',
    '藍' to '蓝',
    '黃' to '黄',
    '銀' to '银',
    '術' to '术',
    '異' to '异',
    '惡' to '恶',
    '夢' to '梦',
    '靈' to '灵',
    '獸' to '兽',
    '隊' to '队',
    '單' to '单',
    '雙' to '双',
    '擊' to '击',
    '劍' to '剑',
    '槍' to '枪',
    '車' to '车',
    '東' to '东',
    '西' to '西',
    '南' to '南',
    '北' to '北',
)

/** 将标签中的常见繁体字转为简体，适合作为展示名称保存。 */
fun simplifyTagName(value: String): String = value.trim().map { ch ->
    TRADITIONAL_TO_SIMPLIFIED[ch] ?: ch
}.joinToString("")

/** 生成标签查重/合并用的归一化 key：简繁转换 + 常见同义别名 + 小写 + 去除空白和常见分隔符。 */
fun normalizeTagKey(value: String): String {
    var key = simplifyTagName(value)
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\s　·・\\-—_]+"), "")
    // 轻百是“轻百合”的常见简称，合并时视为同一标签；已带“合”时不再重复替换。
    key = key.replace(Regex("轻百(?!合)"), "轻百合")
    return key
}
