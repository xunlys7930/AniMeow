use crate::agent::knowledge::SETTINGS_REGISTRY;
use crate::agent::storage::delete_memory;
use crate::agent::types::{MemoryChangeEvent, ProposalItemDiff, SettingsUndoRecord, UiActionCommand};
use crate::{Result, StudioError};
use rusqlite::Connection;
use serde_json::{json, Map, Value};

pub fn exec_navigate_ui(args: &Value) -> Result<(Value, UiActionCommand)> {
    let target = args
        .get("target")
        .and_then(|v| v.as_str())
        .unwrap_or("tab")
        .trim();
    let tab = args.get("tab").and_then(|v| v.as_str()).map(str::trim);
    let sub_view = args.get("sub_view").and_then(|v| v.as_str()).map(str::trim);
    let section = args.get("section").and_then(|v| v.as_str()).map(str::trim);

    let allowed_targets = [
        "tab",
        "settings",
        "manual",
        "maintenance",
        "series_mgr",
        "tag_mgr",
        "character_mgr",
        "sync",
        "quick_capture",
    ];
    if !allowed_targets.contains(&target) {
        return Err(StudioError::InvalidInput(format!(
            "无效的导航目标 `{target}`，可选：{}",
            allowed_targets.join(", ")
        )));
    }

    if let Some(t) = tab {
        let valid_tabs = [
            "curator",
            "calendar",
            "player",
            "companion",
            "universe",
            "vault",
            "yearbook",
        ];
        if !valid_tabs.contains(&t) {
            return Err(StudioError::InvalidInput(format!(
                "无效的工作台 Tab `{t}`，可选：{}",
                valid_tabs.join(", ")
            )));
        }
    }

    let label = match target {
        "settings" => format!("打开偏好设置{}", section.map(|s| format!(" ({s})")).unwrap_or_default()),
        "manual" => "打开动态使用手册".into(),
        "maintenance" => "打开查重整理面板".into(),
        "series_mgr" => "打开系列管理".into(),
        "tag_mgr" => "打开标签管理".into(),
        "character_mgr" => "打开角色管理".into(),
        "sync" => "切换到云同步与本地媒体页".into(),
        "quick_capture" => "呼出灵感速记".into(),
        _ => format!("切换到工作台 {}", tab.unwrap_or("curator")),
    };

    let cmd = UiActionCommand {
        action: "navigate".into(),
        label: label.clone(),
        payload: json!({
            "target": target,
            "tab": tab,
            "subView": sub_view,
            "section": section,
        }),
    };
    Ok((json!({ "ok": true, "executed": label }), cmd))
}

pub fn exec_apply_library_filter(args: &Value) -> Result<(Value, UiActionCommand)> {
    let keyword = args.get("keyword").and_then(|v| v.as_str()).map(str::trim);
    let kind = args.get("kind").and_then(|v| v.as_str()).map(str::trim);
    let status = args.get("status").and_then(|v| v.as_str()).map(str::trim);
    let series = args.get("series").and_then(|v| v.as_str()).map(str::trim);
    let tag = args.get("tag").and_then(|v| v.as_str()).map(str::trim);
    let select_work_id = args.get("select_work_id").and_then(|v| v.as_i64());
    let select_work_kind = args
        .get("select_work_kind")
        .and_then(|v| v.as_str())
        .map(str::trim);
    let open_inspector = args
        .get("open_inspector")
        .and_then(|v| v.as_bool())
        .unwrap_or(select_work_id.is_some());
    let clear = args.get("clear").and_then(|v| v.as_bool()).unwrap_or(false);

    let label = if clear {
        "清空藏品库筛选条件".into()
    } else if let Some(wid) = select_work_id {
        format!("在藏品库中定位并打开作品 #{wid}")
    } else {
        format!(
            "应用藏品库筛选{}",
            keyword
                .or(series)
                .or(tag)
                .or(status)
                .map(|s| format!("：{s}"))
                .unwrap_or_default()
        )
    };

    let cmd = UiActionCommand {
        action: "apply_filter".into(),
        label: label.clone(),
        payload: json!({
            "clear": clear,
            "keyword": keyword,
            "kind": kind,
            "status": status,
            "series": series,
            "tag": tag,
            "selectWorkId": select_work_id,
            "selectWorkKind": select_work_kind,
            "openInspector": open_inspector,
        }),
    };
    Ok((json!({ "ok": true, "executed": label }), cmd))
}

pub fn exec_focus_canvas(args: &Value) -> Result<(Value, UiActionCommand)> {
    let mode = args
        .get("mode")
        .and_then(|v| v.as_str())
        .unwrap_or("ip")
        .trim();
    let normalized_mode = match mode {
        "characters" => "characters",
        "seiyuu" => "seiyuu",
        "ip" | "series" | "all" => "ip",
        _ => {
            return Err(StudioError::InvalidInput(
                "画布模式仅支持 ip / seiyuu / characters".into(),
            ));
        }
    };
    let node_id = args.get("node_id").and_then(|v| v.as_str()).map(str::trim);
    let work_id = args.get("work_id").and_then(|v| v.as_i64());
    let work_kind = args
        .get("work_kind")
        .and_then(|v| v.as_str())
        .unwrap_or("anime");
    let character_id = args.get("character_id").and_then(|v| v.as_i64());

    let resolved_node = node_id
        .map(str::to_string)
        .or_else(|| character_id.map(|cid| format!("char:{cid}")))
        .or_else(|| work_id.map(|wid| format!("{work_kind}:{wid}")));

    let label = match &resolved_node {
        Some(n) => format!("打开关系画布（{normalized_mode}）并聚焦节点 {n}"),
        None => format!("打开关系画布（{normalized_mode} 模式）"),
    };

    let cmd = UiActionCommand {
        action: "focus_canvas".into(),
        label: label.clone(),
        payload: json!({
            "mode": normalized_mode,
            "nodeId": resolved_node,
        }),
    };
    Ok((json!({ "ok": true, "executed": label }), cmd))
}

/// 校验并构造桌面端设置白名单补丁 + 可撤销记录（L2-A）
pub fn build_desktop_settings_update(
    args: &Value,
    current_settings: Option<&Value>,
) -> Result<SettingsUndoRecord> {
    let patch_obj = args
        .get("patch")
        .and_then(|v| v.as_object())
        .or_else(|| args.as_object())
        .ok_or_else(|| StudioError::InvalidInput("需要提供要修改的设置项对象".into()))?;

    let mut clean_patch = Map::new();
    let mut revert_patch = Map::new();
    let mut changes = Vec::new();

    for (key, val) in patch_obj {
        if key == "patch" || key == "reason" {
            continue;
        }
        let valid = match key.as_str() {
            "theme" => val
                .as_str()
                .map(|s| matches!(s, "bunkobon" | "sakuga" | "halide" | "cassette" | "hinoki"))
                .unwrap_or(false),
            "accentHex" => val
                .as_str()
                .map(|s| {
                    s.is_empty()
                        || (s.len() == 7
                            && s.starts_with('#')
                            && s[1..].chars().all(|c| c.is_ascii_hexdigit()))
                })
                .unwrap_or(false),
            "fontScale" => val
                .as_str()
                .map(|s| matches!(s, "compact" | "normal" | "comfortable"))
                .unwrap_or(false),
            "defaultSubView" => val
                .as_str()
                .map(|s| matches!(s, "table" | "kanban" | "shelf"))
                .unwrap_or(false),
            "upscaleMode" => val
                .as_str()
                .map(|s| matches!(s, "off" | "spatial" | "ai-s" | "ai-m"))
                .unwrap_or(false),
            "defaultPlaybackRate" => val
                .as_f64()
                .map(|n| (0.5..=3.0).contains(&n))
                .unwrap_or(false),
            "defaultRestoreStrategy" => val
                .as_str()
                .map(|s| matches!(s, "MERGE_KEEP_LOCAL" | "REPLACE"))
                .unwrap_or(false),
            "coverStoragePolicy" => val
                .as_str()
                .map(|s| matches!(s, "remote" | "local" | "auto"))
                .unwrap_or(false),
            "autoNextEpisode"
            | "autoFallbackLine"
            | "cloudIncludeCovers"
            | "syncDesktopSettings"
            | "closeToTray"
            | "reduceMotion"
            | "autoCheckUpdate" => val.is_boolean(),
            _ => false,
        };

        if !valid {
            return Err(StudioError::InvalidInput(format!(
                "设置项 `{key}` 不在白名单内或取值非法，请先调用 query_app_knowledge 核对合法值"
            )));
        }

        let label = SETTINGS_REGISTRY
            .iter()
            .find(|m| m.key == key.as_str())
            .map(|m| m.label)
            .unwrap_or(key.as_str());
        let old_val = current_settings
            .and_then(|s| s.get(key))
            .cloned()
            .unwrap_or(Value::Null);
        revert_patch.insert(key.clone(), old_val.clone());
        clean_patch.insert(key.clone(), val.clone());
        changes.push(ProposalItemDiff {
            index: changes.len(),
            target_label: label.to_string(),
            action_label: "修改设置".into(),
            before: format_val_brief(&old_val),
            after: format_val_brief(val),
        });
    }

    if clean_patch.is_empty() {
        return Err(StudioError::InvalidInput("未检测到有效的设置修改项".into()));
    }

    let summary = format!(
        "已调整 {} 项桌面设置：{}",
        changes.len(),
        changes
            .iter()
            .map(|c| format!("{} → {}", c.target_label, c.after))
            .collect::<Vec<_>>()
            .join("；")
    );

    Ok(SettingsUndoRecord {
        summary,
        patch: Value::Object(clean_patch),
        revert_patch: Value::Object(revert_patch),
        changes,
    })
}

pub fn exec_update_user_memory(
    local_conn: &Connection,
    args: &Value,
) -> Result<(Value, MemoryChangeEvent)> {
    let action = args.get("action").and_then(|v| v.as_str()).unwrap_or("add");
    match action {
        "delete" => {
            let id = args
                .get("id")
                .and_then(|v| v.as_str())
                .ok_or_else(|| StudioError::InvalidInput("删除记忆需要提供 id".into()))?;
            delete_memory(local_conn, id)?;
            let ev = MemoryChangeEvent {
                action: "deleted".into(),
                id: id.to_string(),
                category: "preference".into(),
                content: "已移除该条记忆".into(),
            };
            Ok((json!({ "ok": true, "deletedId": id }), ev))
        }
        _ => {
            let content = args
                .get("content")
                .and_then(|v| v.as_str())
                .ok_or_else(|| StudioError::InvalidInput("保存记忆需要提供 content".into()))?;
            let category = args
                .get("category")
                .and_then(|v| v.as_str())
                .unwrap_or("preference");
            let tier = args.get("tier").and_then(|v| v.as_str());
            let id = args.get("id").and_then(|v| v.as_str());
            let is_update = id.map(|s| !s.trim().is_empty()).unwrap_or(false);
            let (saved, already_exists) =
                crate::agent::storage::upsert_memory_checked(local_conn, id, category, tier, content, true)?;
            let ev = MemoryChangeEvent {
                action: if already_exists {
                    "deduped"
                } else if is_update {
                    "updated"
                } else {
                    "added"
                }
                .into(),
                id: saved.id.clone(),
                category: saved.category.clone(),
                content: saved.content.clone(),
            };
            Ok((
                json!({
                    "ok": true,
                    "alreadyExists": already_exists,
                    "memory": saved,
                    "note": if already_exists {
                        "同分类与层级下已存在高度重合的记忆，已返回已有条目避免重复建档"
                    } else {
                        "已记入本机单一数据源可编辑记忆库，核心记忆下轮即时生效，界面支持编辑或删除"
                    }
                }),
                ev,
            ))
        }
    }
}

fn format_val_brief(v: &Value) -> String {
    match v {
        Value::Null => "默认".into(),
        Value::Bool(b) => if *b { "开启" } else { "关闭" }.into(),
        Value::String(s) => if s.is_empty() { "跟随主题".into() } else { s.clone() },
        other => other.to_string(),
    }
}
