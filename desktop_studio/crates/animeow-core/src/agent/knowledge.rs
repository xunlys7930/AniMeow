use serde_json::{json, Value};

pub struct SettingMeta {
    pub key: &'static str,
    pub label: &'static str,
    pub section: &'static str,
    pub section_label: &'static str,
    pub allowed_values: &'static str,
    pub description: &'static str,
    pub keywords: &'static [&'static str],
}

pub const SETTINGS_REGISTRY: &[SettingMeta] = &[
    SettingMeta {
        key: "theme",
        label: "界面主题",
        section: "appearance",
        section_label: "外观与界面",
        allowed_values: "bunkobon(文库活版·浅纸), sakuga(作画律表·暖炭暗色), halide(银盐暗房·纯黑暗色), cassette(秋叶原磁带机·复古浅灰), hinoki(桧木书斋·木质浅色)",
        description: "切换整套桌面端色彩、材质与暗/浅色模式。想要暗色护眼推荐 sakuga 或 halide。",
        keywords: &["主题", "暗色", "深色", "浅色", "黑夜", "护眼", "皮肤", "theme", "dark", "light"],
    },
    SettingMeta {
        key: "accentHex",
        label: "强调色",
        section: "appearance",
        section_label: "外观与界面",
        allowed_values: "空字符串(跟随主题) 或 #RRGGBB（如 #2e7d5b 松石绿、#d14d72 绯樱粉、#2b6cb0 晴空蓝、#c07a24 琥珀金、#6c5ce7 紫阳花）",
        description: "自定义按钮、高亮和选中态的主强调色。",
        keywords: &["颜色", "强调色", "主色", "配色", "accent"],
    },
    SettingMeta {
        key: "fontScale",
        label: "界面字号档位",
        section: "appearance",
        section_label: "外观与界面",
        allowed_values: "compact(紧凑), normal(标准), comfortable(舒适偏大)",
        description: "调整全应用文字大小，高分屏或阅读长评时可切 comfortable。",
        keywords: &["字号", "字体", "大小", "太小", "太大", "缩放", "font"],
    },
    SettingMeta {
        key: "defaultSubView",
        label: "藏品库默认视图",
        section: "appearance",
        section_label: "外观与界面",
        allowed_values: "table(表格视图), kanban(看板视图), shelf(画册书架视图)",
        description: "启动桌面端时藏品库默认展示的布局形态。",
        keywords: &["视图", "表格", "看板", "画册", "书架", "默认", "layout", "view"],
    },
    SettingMeta {
        key: "upscaleMode",
        label: "WebGL2 视频超分档位",
        section: "playback",
        section_label: "播放与超分",
        allowed_values: "off(关闭), spatial(锐化增强·轻量省电), ai-s(Anime4K 轻量 AI), ai-m(Anime4K 平衡高画质)",
        description: "内置本地/在线播放器的实时画质超分。如果播放卡顿、掉帧或风扇狂转，建议降到 spatial 或 off。",
        keywords: &["超分", "画质", "卡顿", "掉帧", "播放", "anime4k", "webgl", "显卡", "upscale"],
    },
    SettingMeta {
        key: "defaultPlaybackRate",
        label: "默认播放倍速",
        section: "playback",
        section_label: "播放与超分",
        allowed_values: "0.5 ～ 3.0 之间的数字（常见 1.0 / 1.25 / 1.5 / 2.0）",
        description: "打开视频时的默认播放倍速。",
        keywords: &["倍速", "速度", "快进", "播放", "rate", "speed"],
    },
    SettingMeta {
        key: "autoNextEpisode",
        label: "自动连播下一集",
        section: "playback",
        section_label: "播放与超分",
        allowed_values: "true / false",
        description: "当前集播放完毕后自动切换并播放下一集。",
        keywords: &["连播", "下一集", "自动播放", "playback"],
    },
    SettingMeta {
        key: "autoFallbackLine",
        label: "在线线路自动切换",
        section: "playback",
        section_label: "播放与超分",
        allowed_values: "true / false",
        description: "在线播放遇到线路失效或超时时自动尝试备用线路。",
        keywords: &["线路", "播不了", "失败", "切换", "备用"],
    },
    SettingMeta {
        key: "closeToTray",
        label: "关闭窗口最小化到托盘",
        section: "system",
        section_label: "系统与启动",
        allowed_values: "true(点关闭缩进托盘) / false(点关闭直接退出)",
        description: "控制点击右上角关闭按钮时的行为，开启后软件继续驻留右下角系统托盘。",
        keywords: &["托盘", "关闭", "退出", "后台", "最小化", "tray"],
    },
    SettingMeta {
        key: "reduceMotion",
        label: "减弱界面动效",
        section: "system",
        section_label: "系统与启动",
        allowed_values: "true / false",
        description: "关闭或简化过渡动画，老电脑觉得界面不够流畅或不喜欢动画时建议开启。",
        keywords: &["动效", "动画", "流畅", "卡", "性能", "motion"],
    },
    SettingMeta {
        key: "autoCheckUpdate",
        label: "启动时自动检查更新",
        section: "system",
        section_label: "系统与启动",
        allowed_values: "true / false",
        description: "每次启动桌面端时后台检查是否有新版本。",
        keywords: &["更新", "版本", "升级", "自动检查", "update"],
    },
    SettingMeta {
        key: "cloudIncludeCovers",
        label: "云备份包含本地封面",
        section: "sync",
        section_label: "云端与跨设备",
        allowed_values: "true / false",
        description: "上传云端备份或导出 .animeow.zip 时是否打包本机封面图片（仅含用户手传封面以及因外链不可用或手动离线策略落盘至 covers/native/ 的本地封面；远程引用封面不占备份体积；关闭可进一步减小备份体积）。",
        keywords: &["封面", "本地封面", "备份", "体积", "云同步", "sync", "backup"],
    },
    SettingMeta {
        key: "coverStoragePolicy",
        label: "外部封面存储策略",
        section: "sync",
        section_label: "云端与跨设备",
        allowed_values: "remote(默认，远程探测可用直接引用，失败才落盘本地), auto(同 remote), local(全部下载落盘本地)",
        description: "控制检索入库与元数据回填获取外部封面时的落库方式。默认 remote：先探测外部源 HTTPS 链接可用性，通过则直接存远程引用以控制备份体积，探测失败再回落本地落盘；用户手动上传的封面始终保留本地不受此项影响。",
        keywords: &["封面", "远程封面", "本地封面", "落盘", "备份体积", "coverStoragePolicy", "remote", "local"],
    },
    SettingMeta {
        key: "defaultRestoreStrategy",
        label: "默认备份恢复策略",
        section: "sync",
        section_label: "云端与跨设备",
        allowed_values: "MERGE_KEEP_LOCAL(合并并保留本地较新修改), REPLACE(清空后完全覆写)",
        description: "从手机端或云端恢复备份时的默认合并策略，日常强烈建议 MERGE_KEEP_LOCAL。",
        keywords: &["恢复", "合并", "覆写", "备份", "同步", "restore"],
    },
    SettingMeta {
        key: "syncDesktopSettings",
        label: "跨电脑漫游桌面外观设置",
        section: "sync",
        section_label: "云端与跨设备",
        allowed_values: "true / false",
        description: "是否随备份同步主题、字号等非敏感桌面外观偏好（AI API Key 永不参与同步）。",
        keywords: &["漫游", "设置同步", "换电脑", "云同步"],
    },
];

struct FeatureGuide {
    topic: &'static str,
    title: &'static str,
    path: &'static str,
    summary: &'static str,
    keywords: &'static [&'static str],
}

const FEATURE_GUIDES: &[FeatureGuide] = &[
    FeatureGuide {
        topic: "legacy_migration",
        title: "从旧版桌面端导入数据",
        path: "顶部空库横幅 或 顶部工作台 → 本地媒体与同步 → 旧版桌面端迁移",
        summary: "桌面端启动会自动探测旧版 Electron 目录，一键无损复制作品、系列、标签、角色与本地封面，不删原数据。",
        keywords: &["导入", "迁移", "旧版", "electron", "老数据"],
    },
    FeatureGuide {
        topic: "sync_mobile_bangumi",
        title: "手机端互通、云备份与 Bangumi 双向同步",
        path: "顶部导航 → 本地媒体与同步（vault）",
        summary: "桌面端与 Android 共用同一套 22 张跨端藏品库表，支持 .animeow.zip 本地导入导出、登录追番喵账号云备份，以及绑定 Bangumi 账号双向拉取/上报进度。",
        keywords: &["手机", "安卓", "同步", "云备份", "bangumi", "bgm", "zip", "备份"],
    },
    FeatureGuide {
        topic: "maintenance",
        title: "查重整理、系列管理、标签管理与角色管理",
        path: "左侧栏底部固定操作区：系列管理 / 标签管理 / 角色管理 / 查重整理",
        summary: "可自动扫描疑似重复条目与拆分同系列，支持将重复候选直接归为同系列、合并同义标签、清理无用标签、在线搜索/一键拉取作品角色并维护人物关系与角色标签。",
        keywords: &["重复", "查重", "系列", "标签", "角色", "整理", "合并", "回收站"],
    },
    FeatureGuide {
        topic: "canvas",
        title: "关系画布（系列图谱 & 角色关系图谱）",
        path: "顶部导航 → 关系画布（universe）",
        summary: "支持切换「全部 / 系列 / 角色关系图谱」模式，可开启连线模式直接拉线建关系、点击节点或连线就地编辑、双击角色节点精准打开对应角色详情；单作品角色星图会自动采用分层径向防遮挡排布，在单作品星图内聚焦某个角色并取消聚焦后会自动回到该作品星图而不会切出其他作品；支持导出 2x 高清 PNG 图片。",
        keywords: &["画布", "图谱", "关系", "连线", "聚焦", "星图", "导出", "png", "canvas"],
    },
    FeatureGuide {
        topic: "local_cover",
        title: "本地封面管理与防破图兜底",
        path: "设置 → 云端与跨设备（外部封面存储策略 / 云备份包含本地封面）；藏品详情抽屉 → 编辑作品资料；AI 助手 → 本地封面转远程瘦身",
        summary: "桌面端默认采用「远程优先、失败回落本地」策略（coverStoragePolicy=remote）：检索入库与元数据回填会先探测外部源 HTTPS 封面可用性（200 且 image/*），探测通过直接存远程链接，不占用本地磁盘与备份包体积；仅当外部链接不可达（如 403 防盗链/失效）或用户切为 local 离线模式时才下载落盘至 covers/native/（来源标为 external_native），绝不写入坏链接。用户手传的自定义封面（user_custom）永远保留本地。对于历史已自动落盘的本地封面，可用 AI 助手执行「本地封面转远程引用」瘦身，再经二次确认安全清理无引用孤儿文件。",
        keywords: &["本地封面", "封面", "远程封面", "瘦身", "破图", "防盗链", "covers", "animeow-cover", "图片", "缓存", "孤儿文件"],
    },
    FeatureGuide {
        topic: "metadata_rehydrate",
        title: "检索入库自动补全与骨架资料回填",
        path: "作品详情右侧面板 → 「在线补全资料与封面」；或在 AI 助手里检索入库 / 批量补全",
        summary: "AI 助手通过外部 ID（Bangumi / VNDB / DLsite）检索入库时会自动补全完整条目详情（封面默认经探测存远程 HTTPS 链接，不可达时才落盘本地；标签默认过滤形态/地区/制作组/改编类元数据以保持题材标签墙纯净）。对存量骨架条目，支持单条直连补全或最多 500 条批量回填，严格遵守字段白名单与「只填空值、绝不覆盖用户手写内容」原则，并支持一键整体撤销。",
        keywords: &["封面", "缺封面", "补全", "回填", "骨架", "元数据", "开播日期", "简介", "rehydrate", "检索入库", "同步资料"],
    },
    FeatureGuide {
        topic: "batch_verify",
        title: "大库外部 ID 分批核实与并排对比确认",
        path: "AI 助手抽屉内自动出现的「分批核实任务卡片」",
        summary: "对几十到数百部作品执行外部 ID 核实时，支持断点续跑与分批推进（每次可点击「继续余下」或「仅重试失败项」）。唯一高置信度命中直接归类待绑定，待选或未命中条目会在对话抽屉里展开并排候选对比卡片，可逐条点选「选这个 / 都不匹配 / 稍后处理」，确认后再一键生成绑定提案并支持撤销。",
        keywords: &["核实", "绑定", "bangumi", "外部id", "分批", "批量", "候选", "对比", "verify"],
    },
    FeatureGuide {
        topic: "shortcuts",
        title: "全局快捷键与自定义",
        path: "设置（Ctrl+,） → 快捷键",
        summary: "默认快捷键：Ctrl+K 全局检索、Ctrl+J AI 智能体抽屉、Ctrl+, 偏好设置、Ctrl+B 折叠侧栏、Ctrl+I 详情抽屉、Alt+Space 灵感速记、F1 动态使用手册。全部支持在设置页自定义并自动检测冲突。",
        keywords: &["快捷键", "热键", "ctrl", "shortcut", "速记", "手册"],
    },
    FeatureGuide {
        topic: "personal_score",
        title: "个人评分规则与清空为未评",
        path: "作品详情抽屉 / 独立编辑窗 → 评分；或由 AI 助手批量修改/清空",
        summary: "个人评分采用 0.1～10.0 分制，与外部公开评分（publicScore）分开存储。如需清空单部或批量作品的个人评分恢复为「未评」，可在 propose_work_mutations 的 update/patch 中将 personal_score 设为 null 或 0——系统会真正清空为未评（不会记成 0 分，不污染均分与分数段统计），并自动清理来源标记且支持一键撤销。",
        keywords: &["评分", "打分", "个人评分", "公开评分", "清空评分", "未评", "score", "rating"],
    },
    FeatureGuide {
        topic: "ai_agent",
        title: "AI 助手（私人追番助手）",
        path: "顶部右侧「AI 助手」按钮或快捷键 Ctrl+J；点抽屉右上角配置进入模型与策略设置",
        summary: "支持使用你自己的大模型账号（密钥仅本机加密保存，绝不上传云备份）或本地 Ollama/LM Studio 免密钥运行。可勾选「使用当前筛选结果」把界面筛选的片单直接交给助手，支持缺封面/骨架筛选与批量元数据回填、防剧透聊番、联网查资料（带折叠参考来源与可点击链接）、查看并排核实卡片、操控界面与设置；所有批量改库、合并与回填均先预览对比确认且支持撤销（少数不可一键撤销的操作会在卡片明确提示），并可在轨迹面板导出不含书名与正文隐私的反馈诊断报告。",
        keywords: &["ai", "智能体", "大模型", "key", "ollama", "deepseek", "助手", "防剧透", "撤销", "诊断", "隐私"],
    },
];

pub fn search_app_knowledge(
    topic: &str,
    include_current_settings: bool,
    current_settings: Option<&Value>,
) -> Value {
    let q = topic.trim().to_ascii_lowercase();
    let tokens: Vec<&str> = q
        .split(|c: char| c.is_whitespace() || c == ',' || c == '，' || c == '、')
        .filter(|t| !t.is_empty())
        .collect();

    let mut matched_settings = Vec::new();
    for meta in SETTINGS_REGISTRY {
        let hit = tokens.is_empty()
            || meta.key.to_ascii_lowercase().contains(&q)
            || meta.label.to_ascii_lowercase().contains(&q)
            || meta.section_label.to_ascii_lowercase().contains(&q)
            || meta.description.to_ascii_lowercase().contains(&q)
            || meta.keywords.iter().any(|k| {
                let kl = k.to_ascii_lowercase();
                q.contains(&kl) || tokens.iter().any(|t| kl.contains(t) || t.contains(&kl))
            });
        if hit {
            let current_val = current_settings
                .and_then(|s| s.get(meta.key))
                .cloned()
                .unwrap_or(Value::Null);
            matched_settings.push(json!({
                "key": meta.key,
                "label": meta.label,
                "section": meta.section,
                "sectionLabel": meta.section_label,
                "allowedValues": meta.allowed_values,
                "description": meta.description,
                "currentValue": current_val,
            }));
        }
    }

    let mut matched_guides = Vec::new();
    for g in FEATURE_GUIDES {
        let hit = tokens.is_empty()
            || g.title.to_ascii_lowercase().contains(&q)
            || g.summary.to_ascii_lowercase().contains(&q)
            || g.keywords.iter().any(|k| {
                let kl = k.to_ascii_lowercase();
                q.contains(&kl) || tokens.iter().any(|t| kl.contains(t) || t.contains(&kl))
            });
        if hit {
            matched_guides.push(json!({
                "topic": g.topic,
                "title": g.title,
                "path": g.path,
                "summary": g.summary,
            }));
        }
    }

    // 若关键词没命中任何子集，返回极简目录索引供模型二次精准查询，避免回吐冗长全量设置
    let unmatched = !tokens.is_empty() && matched_settings.is_empty() && matched_guides.is_empty();
    if unmatched {
        let available_settings: Vec<Value> = SETTINGS_REGISTRY
            .iter()
            .map(|meta| {
                json!({
                    "key": meta.key,
                    "label": meta.label,
                    "section": meta.section,
                })
            })
            .collect();
        let available_guides: Vec<Value> = FEATURE_GUIDES
            .iter()
            .map(|g| {
                json!({
                    "topic": g.topic,
                    "title": g.title,
                })
            })
            .collect();
        return json!({
            "unmatched": true,
            "matchedSettings": [],
            "matchedGuides": [],
            "availableSettings": available_settings,
            "availableGuides": available_guides,
            "note": "未命中精确设置或手册条目，可按 availableSettings.key 或 availableGuides.topic 二次查询。"
        });
    }

    let mut result = json!({
        "matchedSettings": matched_settings,
        "matchedGuides": matched_guides,
    });
    if include_current_settings {
        if let Some(cs) = current_settings {
            result["currentSettingsSnapshot"] = cs.clone();
        }
    }
    result
}
