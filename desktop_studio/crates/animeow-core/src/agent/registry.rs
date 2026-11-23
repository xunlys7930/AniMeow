use crate::agent::types::EnabledToolGroups;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

pub const ARTIFACT_SPILL_THRESHOLD_CHARS: usize = 14_000;
pub const ARTIFACT_PREVIEW_CHARS: usize = 2_400;

pub fn tool_meta(name: &str) -> (&'static str, &'static str) {
    match name {
        "read_tool_artifact" => ("翻阅溢写完整结果", "core"),
        "load_skill" => ("加载追番技能规程", "core"),
        "recall_memories" => ("检索归档长期记忆", "memory"),
        "mcp_list_tools" => ("发现外部 MCP 工具", "mcp"),
        "mcp_call_tool" => ("调用外部 MCP 工具", "mcp"),
        "get_library_overview" => ("读取藏品库总览", "read"),
        "query_library" => ("检索藏品库", "read"),
        "read_library_page" => ("按作品翻阅藏品清单", "read"),
        "get_proposal_status" => ("核对提案当前状态", "read"),
        "get_work_detail" => ("查看作品详情", "read"),
        "find_external_id_usage" => ("反查外部条目占用", "read"),
        "get_runtime_stats" => ("查看会话运行与缓存统计", "read"),
        "list_taxonomy" => ("查看系列/标签/角色", "read"),
        "run_library_diagnostics" => ("扫描待整理数据", "read"),
        "query_app_knowledge" => ("查阅软件设置与手册", "read"),
        "search_remote_catalog" => ("在线检索番剧/游戏/角色", "online"),
        "get_discovery_feed" => ("拉取发现页新番与榜单", "online"),
        "web_search" => ("全网联网搜索", "online"),
        "fetch_webpage" => ("阅读指定网页正文", "online"),
        "$web_search" => ("服务商内置联网搜索", "online"),
        "navigate_ui" => ("跳转界面/打开面板", "ui"),
        "apply_library_filter" => ("应用藏品库筛选", "ui"),
        "focus_canvas" => ("聚焦关系画布", "ui"),
        "update_desktop_settings" => ("调整桌面端设置", "ui"),
        "propose_work_mutations" => ("生成作品变更提案", "write"),
        "propose_series_mutations" => ("生成系列整理提案", "write"),
        "propose_tag_mutations" => ("生成标签整理提案", "write"),
        "propose_character_mutations" => ("生成角色与关系提案", "write"),
        "propose_merge_duplicates" => ("生成重复合并提案", "write"),
        "cancel_proposal" => ("撤回待确认提案", "write"),
        "update_user_memory" => ("更新个人偏好记忆", "memory"),
        _ => ("调用工具", "other"),
    }
}

/// 严格 Prefix-Cache 稳定工具表：
/// 跨轮次保持完全确定的工具集合与顺序（绝不在单轮中途动态增删工具导致服务端 KV Cache 击穿）；
/// 外部 MCP 扩展仅暴露 2 个极轻懒加载元工具，绝不撑爆工具 Schema！
pub fn build_stable_tool_schemas(
    enabled: &EnabledToolGroups,
    has_enabled_mcp: bool,
    enable_deepseek_web_tools: bool,
) -> Vec<Value> {
    let mut schemas = Vec::<Value>::new();

    // 核心元工具（Artifact 回读 + 渐进式 Skill 加载）
    schemas.push(fn_schema(
        "read_tool_artifact",
        "回读当前工具输出的字符片段，不能取得藏品查询的下一页。藏品清单使用 read_library_page。",
        json!({
            "type": "object",
            "properties": {
                "artifact_id": { "type": "string" },
                "offset": { "type": "integer" },
                "limit": { "type": "integer" }
            },
            "required": ["artifact_id"]
        }),
    ));
    schemas.push(fn_schema(
        "load_skill",
        "按需加载指定追番技能（如 /整理、/推番、/防剧透、/建关系、/调设置 或自定义技能）的完整操作规程",
        json!({
            "type": "object",
            "properties": {
                "name": { "type": "string", "description": "技能指令名或标题" }
            },
            "required": ["name"]
        }),
    ));

    if enabled.read {
        schemas.push(fn_schema(
            "get_library_overview",
            "获取藏品库整体统计摘要（总数、载体/状态/评分分布、高频标签 Top20、待整理系列与重复数）",
            json!({ "type": "object", "properties": {} }),
        ));
        schemas.push(fn_schema(
            "query_library",
            "按条件检索本地藏品。全量、逐条核查或 CSV 时传 complete=true 保存快照。需要逐条分析时使用 read_library_page。普通查询按 nextOffset 翻页。keywords 批量核对候选是否在库。",
            json!({
                "type": "object",
                "properties": {
                    "keyword": { "type": "string", "description": "单个作品名/原名/制作组/标签关键词" },
                    "keywords": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "批量核查最多 30 个作品名是否已在藏品库中（推番避雷必用，一次查完不要循环调用！）"
                    },
                    "kind": { "type": "string", "enum": ["all", "anime", "book", "game", "audio"] },
                    "status": { "type": "string", "description": "如 在看/看完/想看/搁置/弃坑" },
                    "series": { "type": "string", "description": "所属系列关键词" },
                    "unassigned_series": { "type": "boolean", "description": "是否仅查未归入系列的作品" },
                    "tag": { "type": "string" },
                    "min_score": { "type": "number" },
                    "max_score": { "type": "number" },
                    "score_source": { "type": "string", "enum": ["user", "suspected_external"], "description": "按评分来源筛选已评分条目：suspected_external 筛出与外部公开分几乎一致、疑似历史导入的评分；user 筛出用户本人打分" },
                    "progress_total_unknown": { "type": "boolean", "description": "筛选总数未知的动画、书籍及音声。按时长记录的游戏不在范围内。" },
                    "missing_score": { "type": "boolean", "description": "为 true 时仅筛选尚未填写个人评分的作品" },
                    "missing_tags": { "type": "boolean", "description": "为 true 时仅筛选尚未打任何标签的作品" },
                    "missing_release_date": { "type": "boolean", "description": "为 true 时仅筛选发售/开播日期为空的作品" },
                    "missing_cover": { "type": "boolean", "description": "为 true 时仅筛选缺少有效封面的作品" },
                    "incomplete_metadata": { "type": "boolean", "description": "为 true 时筛选缺失任一核心元数据（默认封面/日期/标签，可用 incomplete_fields 自定义）的骨架记录" },
                    "incomplete_fields": {
                        "type": "array",
                        "items": { "type": "string", "enum": ["cover", "release_date", "tags", "score", "studio", "external_id", "total_episodes"] },
                        "description": "与 incomplete_metadata=true 联用，指定必填字段集"
                    },
                    "missing_external_id": { "type": "boolean", "description": "为 true 时筛选尚未绑定任何外部条目 ID 的作品（与 missing_external_source 互斥）" },
                    "missing_external_source": { "type": "string", "enum": ["bangumi", "vndb", "dlsite"], "description": "按指定外部来源筛选未绑定作品：bangumi 仅筛动画与书籍（或显式指定 kind=game 查游戏），vndb 仅筛游戏，dlsite 仅筛音声；与 missing_external_id 互斥" },
                    "sort": { "type": "string", "enum": ["recent", "score_desc", "score_asc", "title"] },
                    "complete": { "type": "boolean", "description": "本地生成可查看和导出的清单，省略 limit 则取全部。生成清单不等于逐条核验。" },
                    "limit": { "type": "integer", "description": "普通查询的单页条数 1~30，完整清单模式下是用户明确指定的最大条数" },
                    "offset": { "type": "integer" }
                }
            }),
        ));
        schemas.push(fn_schema(
            "read_library_page",
            "按作品记录读取当前对话的保存清单（含原名、外部绑定、进度单位与形态）。按下一页游标继续，不能把读取数说成外部核验数。",
            json!({"type":"object","properties":{
                "result_id":{"type":"string"},
                "offset":{"type":"integer"},
                "limit":{"type":"integer","description":"每页最多 30 条；仅抽样核对或取单条 ID 试探时优先传小值（如 3~5）以节省上下文"}
            },"required":["result_id"]}),
        ));
        schemas.push(fn_schema(
            "get_proposal_status",
            "核对当前对话的提案状态（含实际已执行项回执、appliedIndices、分页明细与新建系列 ID）。回答是否生效前使用此工具。省略 ID 时返回最近十项。",
            json!({"type":"object","properties":{
                "proposal_id":{"type":"string"},
                "offset":{"type":"integer","description":"查询单个提案明细时的分页偏移（默认 0，已执行项优先排前）"},
                "limit":{"type":"integer","description":"单页返回条目数 1~100，默认 50"}
            }}),
        ));
        schemas.push(fn_schema(
            "get_work_detail",
            "读取单部或批量（work_ids 最多 30 部）作品档案，含原名、精确进度与单位、作品形态、标签、系列、外部ID、出场角色、短评/长评；传 include_deleted=true 可查回收站条目。",
            json!({
                "type": "object",
                "properties": {
                    "work_id": { "type": "integer", "description": "单部作品 ID" },
                    "work_ids": {
                        "type": "array",
                        "items": { "type": "integer" },
                        "description": "批量查询最多 30 部作品详情（与 work_id 二选一）"
                    },
                    "kind": { "type": "string", "enum": ["anime", "book", "game", "audio"] },
                    "title_keyword": { "type": "string", "description": "不知道 work_id 时用片名搜索" },
                    "include_review": { "type": "boolean", "description": "是否读取长评全文，默认 false 省 token" },
                    "include_deleted": { "type": "boolean", "description": "为 true 时允许查询回收站中的软删除作品" },
                    "probe_cover": { "type": "boolean", "description": "是否联网探测未缓存的远程封面可用性，默认 false（本地秒返不阻塞）；仅排查封面是否破图时传 true" }
                }
            }),
        ));
        schemas.push(fn_schema(
            "find_external_id_usage",
            "按外部来源 ID（支持单个或最多 30 个批量）反查本地占用方，自动覆盖在库与回收站软删除条目，用于排查绑定冲突。",
            json!({
                "type": "object",
                "properties": {
                    "source": { "type": "string", "enum": ["bangumi", "vndb", "dlsite"], "description": "可选，省略时检索全部来源" },
                    "external_id": { "type": "string", "description": "单个外部条目 ID（如 Bangumi 数字 ID、VNDB v123、DLsite RJ 号）" },
                    "external_ids": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "批量反查最多 30 个外部条目 ID"
                    }
                }
            }),
        ));
        schemas.push(fn_schema(
            "get_runtime_stats",
            "查看当前会话的运行轮次、工具调用次数与输出字符分布、估算上下文占用及服务端缓存命中率，便于决定批量查询与核实节奏。",
            json!({ "type": "object", "properties": {} }),
        ));
        schemas.push(fn_schema(
            "list_taxonomy",
            "分页查询系列、标签或角色资料摘要。角色结果包含头像、简介、长评是否存在以及关联作品的声优。",
            json!({
                "type": "object",
                "properties": {
                    "entity": { "type": "string", "enum": ["series", "tag", "character"] },
                    "keyword": { "type": "string" },
                    "limit": { "type": "integer" },
                    "offset": { "type": "integer" },
                    "relation_limit": { "type": "integer", "description": "entity=character 时每名角色最多返回的关系条数（默认 15，最高 50）" },
                    "work_limit": { "type": "integer", "description": "entity=character 时每名角色最多返回的关联作品条数（默认 10，最高 30）" }
                },
                "required": ["entity"]
            }),
        ));
        schemas.push(fn_schema(
            "run_library_diagnostics",
            "运行桌面端内置诊断算法，扫描待归并同系列、疑似重复条目、0 使用孤立标签、全库封面分布与磁盘占用(covers)、以及存量非常规日期格式(dates，只报不改)",
            json!({
                "type": "object",
                "properties": {
                    "scope": { "type": "string", "enum": ["all", "series", "duplicates", "unused_tags", "covers", "dates"] },
                    "offset": { "type": "integer", "description": "继续读取的位置，分页时请指定 scope" }
                }
            }),
        ));
        schemas.push(fn_schema(
            "query_app_knowledge",
            "查询当前版本追番喵桌面端的全部设置项说明、合法取值、功能操作路径与用户当前实时设置值",
            json!({
                "type": "object",
                "properties": {
                    "topic": { "type": "string", "description": "关键词，如 超分/主题/同步/画布/快捷键/备份" },
                    "include_current_settings": { "type": "boolean" }
                }
            }),
        ));
    }

    if enabled.online {
        schemas.push(fn_schema(
            "search_remote_catalog",
            "在线检索作品、角色或放送表。核实库内动画或书籍时：1) 推荐直接传 result_id（或已有 verify_job_id）由本地程序分批持久化核实并支持断点续跑/重试失败项，默认只回传统计与疑似/失败例外项大幅省 Token；2) 也可传 work_ids（1~50 部）或单部 work_id + kind（compact=false 时补全 tags/coverUrl/summary/characters 完整元数据，缺字段显式返回 null）。拉取作品角色表传 source=\"character\" + bangumi_subject_id。",
            json!({
                "type": "object",
                "properties": {
                    "source": { "type": "string", "enum": ["bangumi", "book", "game", "character", "calendar"] },
                    "keyword": { "type": "string", "description": "关键词；与 bangumi_subject_id 同传时在作品角色表内过滤角色名/声优/简介" },
                    "result_id": { "type": "string", "description": "直接传入本会话保存的藏品清单标识（art_...），由本地自动创建可续跑核实任务并跑首批" },
                    "verify_job_id": { "type": "string", "description": "继续执行已有可续跑核实任务（vjob_...）的下一批或重试失败项" },
                    "batch_action": { "type": "string", "enum": ["start", "continue", "retry_failed"], "description": "配合 verify_job_id 使用：continue 继续下一批（有处理进展且还有剩余时可同参续跑），retry_failed 仅重试失败项" },
                    "batch_size": { "type": "integer", "description": "每批核实条数 1~50，默认 30" },
                    "work_id": { "type": "integer", "description": "核实库内单部作品时填写该作品 ID（与 work_ids 互斥）" },
                    "work_ids": {
                        "type": "array",
                        "items": { "type": "integer" },
                        "description": "批量核实库内 1~50 部动画或书籍的作品 ID 数组（与 work_id 互斥，超出 50 将报错）"
                    },
                    "kind": { "type": "string", "enum": ["anime", "book"], "description": "填写 work_id 或 work_ids 时必需；传 result_id 时可选（默认 all）" },
                    "compact": { "type": "boolean", "description": "批量核实模式默认 true（紧凑投影不触发归档）；设 false 可看完整 evidence、tags、coverUrl、summary 与角色摘要" },
                    "max_candidates": { "type": "integer", "description": "可选：限制每部返回的候选数 1~8（精确匹配时默认精简）" },
                    "bangumi_subject_id": { "type": "integer", "description": "source=character 时按 Bangumi 作品 ID 拉取整部作品出场角色（已按主角/主要角色优先排序）" },
                    "offset": { "type": "integer", "description": "source=character + bangumi_subject_id 分页偏移（默认 0）" },
                    "limit": { "type": "integer", "description": "source=character 返回条数（角色表默认 30 最高 50；全局搜角色默认 15 最高 30）" },
                    "day": { "type": "integer", "description": "source=calendar 时可选星期 1~7" }
                },
                "required": ["source"]
            }),
        ));
        schemas.push(fn_schema(
            "get_discovery_feed",
            "拉取追番喵自有发现页榜单（全模型免费通用）：当季新番、下季即将开播、热门榜单、高分口碑榜，支持按动画/书籍/游戏与标签筛选",
            json!({
                "type": "object",
                "properties": {
                    "kind": { "type": "string", "enum": ["anime", "book", "game"], "description": "默认 anime" },
                    "feed": { "type": "string", "enum": ["seasonal", "upcoming", "popular", "ranking"], "description": "seasonal=当季/指定季度新番, upcoming=下季即将开播, popular=热门推荐, ranking=口碑高分榜" },
                    "sort": { "type": "string", "enum": ["heat", "score", "rank", "date"] },
                    "year": { "type": "integer", "description": "可选年份（feed=seasonal 时有效，默认当前年）" },
                    "month": { "type": "integer", "description": "可选季度首月 1/4/7/10（feed=seasonal 时有效，默认当前季）" },
                    "tag": { "type": "string", "description": "可选题材标签，如 百合/悬疑/轻小说" },
                    "query": { "type": "string", "description": "可选关键词过滤" },
                    "page": { "type": "integer", "description": "页码，默认 1" },
                    "limit": { "type": "integer", "description": "返回条数 1~20，默认 12" }
                }
            }),
        ));
        if enable_deepseek_web_tools {
            schemas.push(fn_schema(
                "web_search",
                "调用 DeepSeek 官方全网搜索，查证最新定档公告、轻小说/漫画最新出版卷数、制作组访谈或泛全网口碑（支持 site:、-site:、after:、before: 语法；查番剧/榜单优先用 get_discovery_feed 或 search_remote_catalog）",
                json!({
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "精准搜索关键词" },
                        "count": { "type": "integer", "description": "返回结果条数 1~10，默认 5" }
                    },
                    "required": ["query"]
                }),
            ));
            schemas.push(fn_schema(
                "fetch_webpage",
                "调用 DeepSeek 官方网页抓取，阅读指定公开网页（如百科/出版社/新闻页）的干净正文 Markdown 以核实权威细节",
                json!({
                    "type": "object",
                    "properties": {
                        "url": { "type": "string", "description": "完整的 http:// 或 https:// 公开网页地址" }
                    },
                    "required": ["url"]
                }),
            ));
        }
    }

    if enabled.ui {
        schemas.push(fn_schema(
            "navigate_ui",
            "操控软件界面：切换主工作台 Tab、子视图，或打开设置/手册/查重/系列/标签/角色管理/同步弹窗",
            json!({
                "type": "object",
                "properties": {
                    "target": { "type": "string", "enum": ["tab", "settings", "manual", "maintenance", "series_mgr", "tag_mgr", "character_mgr", "sync", "quick_capture"] },
                    "tab": { "type": "string", "enum": ["curator", "calendar", "player", "companion", "universe", "vault", "yearbook"] },
                    "sub_view": { "type": "string", "enum": ["table", "kanban", "shelf"] },
                    "section": { "type": "string", "description": "settings 分区：appearance/system/shortcuts/playback/sync/storage" }
                },
                "required": ["target"]
            }),
        ));
        schemas.push(fn_schema(
            "apply_library_filter",
            "在藏品库主界面应用筛选条件（关键词/载体/状态/系列/标签），或直接选中某部作品打开详情抽屉",
            json!({
                "type": "object",
                "properties": {
                    "clear": { "type": "boolean" },
                    "keyword": { "type": "string" },
                    "kind": { "type": "string", "enum": ["all", "anime", "book", "game", "audio"] },
                    "status": { "type": "string" },
                    "series": { "type": "string" },
                    "tag": { "type": "string" },
                    "select_work_id": { "type": "integer" },
                    "select_work_kind": { "type": "string", "enum": ["anime", "book", "game", "audio"] },
                    "open_inspector": { "type": "boolean" }
                }
            }),
        ));
        schemas.push(fn_schema(
            "focus_canvas",
            "跳转到关系画布并切换模式，同时聚焦到指定作品或角色节点",
            json!({
                "type": "object",
                "properties": {
                    "mode": { "type": "string", "enum": ["all", "series", "characters"] },
                    "work_id": { "type": "integer" },
                    "work_kind": { "type": "string", "enum": ["anime", "book", "game", "audio"] },
                    "character_id": { "type": "integer" }
                },
                "required": ["mode"]
            }),
        ));
        schemas.push(fn_schema(
            "update_desktop_settings",
            "直接修改桌面端白名单偏好设置（附带一键撤销）。修改前建议先用 query_app_knowledge 核对取值",
            json!({
                "type": "object",
                "properties": {
                    "patch": {
                        "type": "object",
                        "description": "键值对，如 { \"theme\": \"sakuga\", \"fontScale\": \"comfortable\", \"upscaleMode\": \"spatial\", \"closeToTray\": false }"
                    }
                },
                "required": ["patch"]
            }),
        ));
    }

    if enabled.write {
        schemas.push(fn_schema(
            "propose_work_mutations",
            "提交作品变更提案（单次最多 500 项，支持整体撤销）。支持五种模式：1) operations 逐项增删改（create 与 update 均支持 release_date / cover_url / workType / mediaFormat / movieFormat / progress_primary / progress_total / tags / studio_or_circle / synopsis；create 带外部 ID 时会自动拉取完整详情，封面默认经探测优先用远程 HTTPS 链接、不可达才回落本地）；2) result_id + patch 批量改状态/评分/标签；3) mode=\"bulk_bind\" + from_verify_ids 或 bindings 批量绑定 Bangumi；4) mode=\"rehydrate\" + work_id/work_ids/result_id + fields 白名单只填空值补全元数据（封面默认远程优先以控制备份体积，标签默认过滤形态/地区/制作组元数据，绝不覆盖用户手填值）；5) mode=\"migrate_covers_to_remote\" 将程序自动落盘（external_native）且远程探测可用的本地封面瘦身替换为标准远程链接，强制排除用户自定义封面，探测失败保留本地。",
            json!({
                "type": "object",
                "properties": {
                    "title": { "type": "string" },
                    "reason": { "type": "string" },
                    "mode": { "type": "string", "enum": ["bulk_bind", "rehydrate", "migrate_covers_to_remote"], "description": "bulk_bind=批量绑定外部ID；rehydrate=按字段白名单只填空值回填元数据；migrate_covers_to_remote=将程序自动落盘的本地封面瘦身替换为探测可用的远程链接（自动排除用户手传封面）" },
                    "work_id": { "type": "integer", "description": "mode=rehydrate 或 migrate_covers_to_remote 单部操作时的作品 ID" },
                    "work_ids": {
                        "type": "array",
                        "items": { "type": "integer" },
                        "description": "mode=rehydrate 或 migrate_covers_to_remote 批量操作时的作品 ID 数组（最多 500 部）"
                    },
                    "kind": { "type": "string", "enum": ["anime", "book", "game", "audio"], "description": "可选指定载体防同号歧义" },
                    "fields": {
                        "type": "array",
                        "items": { "type": "string", "enum": ["cover", "release_date", "tags", "summary", "characters", "total_episodes", "workType", "mediaFormat", "studio", "original_title"] },
                        "description": "mode=rehydrate 字段白名单（硬约束：白名单外不读不写；仅填空值，绝不覆盖用户手填值）"
                    },
                    "cover_storage_policy": {
                        "type": "string",
                        "enum": ["remote", "local", "auto"],
                        "description": "mode=rehydrate 可选覆盖本次封面策略：remote/auto=远程探测通过直接存远程 URL，失败才落盘本地；local=强制落盘本地"
                    },
                    "filter_metadata_tags": {
                        "type": "boolean",
                        "description": "mode=rehydrate 回填标签时是否过滤形态/地区/改编/制作组类元数据标签（默认 true）"
                    },
                    "from_verify_ids": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "推荐：直接传入 search_remote_catalog 批量核实返回的 verifyId 数组，免手抄所有 exact_match 绑定项"
                    },
                    "exclude_work_ids": {
                        "type": "array",
                        "items": { "type": "integer" },
                        "description": "可选：与 from_verify_ids 联用时排除指定 work_id"
                    },
                    "bindings": {
                        "type": "array",
                        "description": "bulk_bind 模式下的手动绑定数组（最多 500 项，可与 from_verify_ids 合并）",
                        "items": {
                            "type": "object",
                            "properties": {
                                "work_id": { "type": "integer" },
                                "kind": { "type": "string", "enum": ["anime", "book"] },
                                "external_source": { "type": "string", "enum": ["bangumi"] },
                                "external_id": { "type": "string", "description": "已核实有效的 Bangumi 数字条目 ID" },
                                "original_title": { "type": "string", "description": "可选：仅在本地原名为空时回填，不覆盖用户已填原名" },
                                "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                            },
                            "required": ["work_id", "kind", "external_id"]
                        }
                    },
                    "result_id": { "type": "string", "description": "本对话的完整藏品清单标识，用于 patch 批改或 mode=rehydrate 回填" },
                    "patch": {
                        "type": "object",
                        "properties": {
                            "status": { "type": "string", "description": "按载体指定状态，动画看完、游戏已玩、音声已听；混合清单只能用共同支持的状态（如 搁置/弃坑）" },
                            "personal_score": { "type": "number", "minimum": 0, "maximum": 10, "description": "0.1~10 分制（如 9.5）；传 0 或 null 可清空个人评分恢复为未评（不会记成 0 分）" },
                            "tags": { "type": "array", "items": { "type": "string" }, "description": "整组替换为此标签列表（与 add_tags/remove_tags 互斥）" },
                            "add_tags": { "type": "array", "items": { "type": "string" }, "description": "在保留每部作品原有标签的前提下追加指定标签（自动去重，无需改动的作品自动跳过）" },
                            "remove_tags": { "type": "array", "items": { "type": "string" }, "description": "从每部作品现有标签中移除指定标签（保留其余原标签）" }
                        }
                    },
                    "operations": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "action": { "type": "string", "enum": ["create", "update", "soft_delete"] },
                                "kind": { "type": "string", "enum": ["anime", "book", "game", "audio"] },
                                "work_id": { "type": "integer", "description": "更新和删除时同时传入 kind，编号可能与其他载体重复" },
                                "expected_title": { "type": "string", "description": "可选防误写校验：update/soft_delete 时核对目标作品标题，不符直接拒收并返回 TITLE_MISMATCH" },
                                "title": { "type": "string" },
                                "original_title": { "type": "string", "description": "update 模式下仅在本地原名为空时回填，不覆盖手工原名" },
                                "release_date": { "type": "string", "description": "create 与 update 模式均支持开播/发售日（YYYY-MM-DD / YYYY-MM / YYYY，update 传空字符串可清空）" },
                                "studio_or_circle": { "type": "string", "description": "制作公司/社团/开发者" },
                                "media_format": { "type": "string", "description": "作品形态（如 TV/剧场版/剧场版动画/OVA/OAD/WEB/总集篇/漫画/轻小说）" },
                                "mediaFormat": { "type": "string", "description": "media_format 的 camelCase 别名，create 与 update 均支持" },
                                "workType": { "type": "string", "description": "显式作品形态（剧场版/TV/OVA/OAD/WEB/总集篇等），create 与 update 均支持" },
                                "movieFormat": { "type": "boolean", "description": "是否为剧场版（true=剧场版，false=非剧场版），不可与 workType/mediaFormat 冲突" },
                                "synopsis": { "type": "string", "description": "作品简介" },
                                "series_name": { "type": "string", "description": "create 模式下可选直接归入的系列名称" },
                                "series_role": { "type": "string", "description": "create 模式下归入系列时的定位，如 第二季/续作" },
                                "cover_url": { "type": "string", "description": "封面链接或本地封面标记 animeow-cover://（update 传空字符串可清空）" },
                                "status": { "type": "string" },
                                "progress_primary": { "type": "integer", "description": "当前已看/已读进度（create 与 update 均支持，需满足 <= progress_total）" },
                                "progress_total": { "type": "integer" },
                                "total_episodes": { "type": "integer" },
                                "personal_score": { "type": "number", "description": "0.1~10 分制（如 9.5）；update 模式下传 0 或 null 可清空个人评分恢复为未评（不会记成 0 分）" },
                                "memo": { "type": "string" },
                                "review": { "type": "string" },
                                "tags": { "type": "array", "items": { "type": "string" } },
                                "field_source": { "type": "string", "enum": ["user", "external", "inferred"], "description": "可选：标记写入字段来源，区分用户手填、外部事实与推断值" },
                                "external_source": { "type": "string", "description": "给未绑定的动画或书籍绑定或检索入库时传 bangumi，并同时给出已核对的 external_id" },
                                "external_id": { "type": "string", "description": "目标外部条目 ID。带入 create 时会自动拉取完整详情（封面默认经探测优先使用远程 HTTPS 链接，不可达时回落本地）" }
                            },
                            "required": ["action"]
                        }
                    }
                }
            }),
        ));
        schemas.push(fn_schema(
            "propose_series_mutations",
            "提交系列归类、重命名、合并、调整定位、移出成员或删除零成员系列的变更提案。assign_series 使用 targets 数组且每项可单独指定 role（如 第一季/续作/剧场版），也允许在同一提案内混用 assign_series 与 update_member_role；确认生效后可在 get_proposal_status 查看新建 seriesId。",
            json!({
                "type": "object",
                "properties": {
                    "title": { "type": "string" },
                    "reason": { "type": "string" },
                    "operations": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "action": { "type": "string", "enum": ["assign_series", "rename_series", "merge_series", "update_member_role", "remove_member", "delete_empty_series"] },
                                "series_name": { "type": "string", "description": "assign_series 必需：系列名称（不存在时确认后自动创建）" },
                                "series_role": { "type": "string", "description": "assign_series 可选默认定位（当 targets 项未单独指定 role 时生效）" },
                                "targets": {
                                    "type": "array",
                                    "description": "assign_series 推荐方式：成员数组，每项可自带 role（单部归入时也兼容顶层 work_id/kind）",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "kind": { "type": "string", "enum": ["anime", "book", "game", "audio"] },
                                            "work_id": { "type": "integer" },
                                            "role": { "type": "string", "description": "该成员在系列中的定位，如 第一季/续作/剧场版/外传/原作" }
                                        },
                                        "required": ["work_id"]
                                    }
                                },
                                "series_id": { "type": "integer", "description": "rename_series / remove_member / delete_empty_series 必需；update_member_role 可省略（自动按作品当前系列定位）" },
                                "source_series_id": { "type": "integer", "description": "merge_series 必需" },
                                "target_series_id": { "type": "integer", "description": "merge_series 必需" },
                                "new_name": { "type": "string", "description": "rename_series 必需" },
                                "work_id": { "type": "integer", "description": "update_member_role / remove_member 必需；assign_series 单部归入时也兼容" },
                                "kind": { "type": "string", "enum": ["anime", "book", "game", "audio"], "description": "update_member_role / remove_member 载体" },
                                "role": { "type": "string", "description": "update_member_role 定位文案（传空字符串表示清除定位）" }
                            },
                            "required": ["action"]
                        }
                    }
                },
                "required": ["operations"]
            }),
        ));
        schemas.push(fn_schema(
            "propose_tag_mutations",
            "提交标签重命名、同义合并、清理 0 使用标签或切换屏蔽的变更提案（用户确认后写入，清理 0 使用标签支持逐项勾选与一键撤销）。清理全部 0 使用标签只需传 1 条不带 tag_id 的 purge_unused_tags，系统会自动展开为逐条具名清单；也可传具体 tag_id 清理指定 0 使用标签（自动去重）。",
            json!({
                "type": "object",
                "properties": {
                    "title": { "type": "string" },
                    "reason": { "type": "string" },
                    "operations": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "action": { "type": "string", "enum": ["rename_tag", "merge_tags", "purge_unused_tags", "toggle_tag_blocked"] },
                                "tag_id": { "type": "integer", "description": "rename_tag / toggle_tag_blocked 必填；purge_unused_tags 可选（传则清理指定 0 使用标签，省略则自动展开全部 0 使用标签）" },
                                "source_tag_id": { "type": "integer" },
                                "target_tag_id": { "type": "integer" },
                                "new_name": { "type": "string" },
                                "blocked": { "type": "boolean" }
                            },
                            "required": ["action"]
                        }
                    }
                },
                "required": ["operations"]
            }),
        ));
        schemas.push(fn_schema(
            "propose_character_mutations",
            "提交角色建档、绑定/解除出场作品、建立/删除人物关系、删除角色或设置角色标签的提案（用户确认后写入，且全部支持一键撤销）。编辑已有角色、出场关联或已有关系时，省略的字段保留；需要清空文本时显式传空字符串。只补译名可传 action=upsert_character、id 和 name_cn。link_work / unlink_work 建议传 expected_title 防写错作品 ID，返回回执会列出 affectedWorks 真实作品名。",
            json!({
                "type": "object",
                "properties": {
                    "title": { "type": "string" },
                    "reason": { "type": "string" },
                    "operations": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "action": { "type": "string", "enum": ["upsert_character", "link_work", "unlink_work", "upsert_relation", "delete_relation", "delete_character", "set_tags"] },
                                "id": { "type": "integer" },
                                "bgm_id": { "type": "integer" },
                                "name": { "type": "string" },
                                "name_cn": { "type": "string" },
                                "image_url": { "type": "string" },
                                "summary": { "type": "string" },
                                "anime_id": { "type": "integer" },
                                "expected_title": { "type": "string", "description": "可选防误写校验：传作品标题关键词，与 anime_id 实际标题不符时拒收并返回 TITLE_MISMATCH" },
                                "character_id": { "type": "integer" },
                                "character_name": { "type": "string" },
                                "role_name": { "type": "string" },
                                "voice_actor": { "type": "string" },
                                "relation_id": { "type": "integer", "description": "delete_relation 可直接按关系 ID 删除，或传 source+target 匹配删除" },
                                "source_character_id": { "type": "integer" },
                                "target_character_id": { "type": "integer" },
                                "source_name": { "type": "string" },
                                "target_name": { "type": "string" },
                                "relation_type": { "type": "string" },
                                "note": { "type": "string" },
                                "tags": { "type": "array", "items": { "type": "string" } }
                            },
                            "required": ["action"]
                        }
                    }
                },
                "required": ["operations"]
            }),
        ));
        schemas.push(fn_schema(
            "propose_merge_duplicates",
            "提交疑似重复条目合并提案（用户确认后保留主条目并将多余项移入回收站）",
            json!({
                "type": "object",
                "properties": {
                    "title": { "type": "string" },
                    "reason": { "type": "string" },
                    "merges": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "kind": { "type": "string" },
                                "keep_id": { "type": "integer" },
                                "drop_ids": { "type": "array", "items": { "type": "integer" } }
                            },
                            "required": ["keep_id", "drop_ids"]
                        }
                    }
                },
                "required": ["merges"]
            }),
        ));
        schemas.push(fn_schema(
            "cancel_proposal",
            "主动撤回本会话中尚未被用户确认（pending）的变更提案。仅在发现刚提交的待确认提案存在笔误（如作品 ID 写错）时调用，不能撤销已确认落库的提案。",
            json!({
                "type": "object",
                "properties": {
                    "proposal_id": { "type": "string", "description": "待撤回提案的 proposalId" },
                    "reason": { "type": "string", "description": "撤回原因" }
                },
                "required": ["proposal_id"]
            }),
        ));
    }

    if enabled.memory {
        schemas.push(fn_schema(
            "recall_memories",
            "在本地单一数据源长期记忆库中按关键词、分类或层级检索记忆（支持中文意图同义识别与多词 AND 过滤；留空返回所选 tier 下的最新记忆，默认 tier=all 包含 core 与 archive）",
            json!({
                "type": "object",
                "properties": {
                    "query": { "type": "string", "description": "搜索关键词或自然问句；留空则返回当前 tier 下的最新记忆（默认 core+archive，若仅查归档请传 tier=archive）" },
                    "category": { "type": "string", "enum": ["all", "preference", "rating_rule", "spoiler_rule", "style", "avoid", "profile"] },
                    "tier": { "type": "string", "enum": ["all", "core", "archive"], "description": "层级过滤：all（默认，含 core+archive）、core（仅核心常驻）、archive（仅归档）" },
                    "match_mode": { "type": "string", "enum": ["and", "or"], "description": "多词匹配模式，默认 and（所有词元均须命中）" }
                }
            }),
        ));
        schemas.push(fn_schema(
            "update_user_memory",
            "记录或删除用户的长期口味、评分习惯或称呼偏好（tier=core 常驻，tier=archive 归档按需检索）",
            json!({
                "type": "object",
                "properties": {
                    "action": { "type": "string", "enum": ["add", "delete"] },
                    "id": { "type": "string" },
                    "category": { "type": "string", "enum": ["preference", "rating_rule", "spoiler_rule", "style", "avoid", "profile"] },
                    "tier": { "type": "string", "enum": ["core", "archive"] },
                    "content": { "type": "string" }
                }
            }),
        ));
    }

    if enabled.mcp && has_enabled_mcp {
        schemas.push(fn_schema(
            "mcp_list_tools",
            "发现已启用的外部扩展服务（MCP）及其可用工具清单与参数说明",
            json!({
                "type": "object",
                "properties": {
                    "server_name": { "type": "string", "description": "可选，指定单个 MCP 服务名" }
                }
            }),
        ));
        schemas.push(fn_schema(
            "mcp_call_tool",
            "调用外部 MCP Server 的指定工具（写操作将自动生成待用户确认提案，外部结果会做防注入隔离）",
            json!({
                "type": "object",
                "properties": {
                    "server_name": { "type": "string" },
                    "tool_name": { "type": "string" },
                    "arguments": { "type": "object" }
                },
                "required": ["server_name", "tool_name"]
            }),
        ));
    }

    schemas
}

fn fn_schema(name: &str, description: &str, parameters: Value) -> Value {
    json!({
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": parameters,
        }
    })
}

/// 生成规范化的 `(tool_name, args)` 签名，用于检测完全重复调用死循环
pub fn canonical_call_signature(name: &str, args: &Value) -> String {
    let serialized = serde_json::to_string(args).unwrap_or_default();
    let mut hasher = Sha256::new();
    hasher.update(name.as_bytes());
    hasher.update(b"::");
    hasher.update(serialized.as_bytes());
    hex::encode(hasher.finalize())
}

pub fn truncate_tool_output(raw: String) -> (String, bool) {
    let count = raw.chars().count();
    if count <= ARTIFACT_SPILL_THRESHOLD_CHARS {
        return (raw, false);
    }
    let kept: String = raw.chars().take(ARTIFACT_PREVIEW_CHARS).collect();
    (
        format!("{kept}\n... [输出过长已截断，原长 {count} 字符]"),
        true,
    )
}
