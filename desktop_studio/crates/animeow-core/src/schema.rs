use crate::Result;
use rusqlite::Connection;
use std::collections::HashSet;
use std::path::Path;

pub const BACKUP_TABLES: &[&str] = &[
    "media_series",
    "media_series_members",
    "audio_works",
    "games",
    "watch_statuses",
    "series",
    "animes",
    "tags",
    "anime_tags",
    "watch_records",
    "episode_details",
    "book_reading_states",
    "book_bookmarks",
    "anime_analysis_records",
    "characters",
    "anime_characters",
    "character_relations",
    "character_tags",
    "character_tag_links",
    "character_groups",
    "character_group_characters",
    "character_group_works",
];

pub const CLEAR_ORDER: &[&str] = &[
    "media_series_members",
    "media_series",
    "audio_works",
    "games",
    "character_group_works",
    "character_group_characters",
    "character_groups",
    "character_tag_links",
    "character_tags",
    "character_relations",
    "anime_characters",
    "characters",
    "anime_analysis_records",
    "watch_records",
    "book_bookmarks",
    "book_reading_states",
    "episode_details",
    "anime_tags",
    "tags",
    "animes",
    "series",
    "watch_statuses",
];

/// 初始化跨端同步主库 `animeow.db`。跨端备份只导出契约列出的表。
pub fn init_sync_db(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let conn = Connection::open(path)?;
    configure_connection(&conn)?;
    apply_sync_schema(&conn)?;
    // 凭据必须随藏品事务提交。此表不参与跨端备份和恢复。
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS agent_proposal_commits (id TEXT PRIMARY KEY NOT NULL);",
    )?;
    Ok(conn)
}

/// 初始化桌面端私有扩展库 `desktop_local.db`（存储挂载目录、本地媒体索引、游玩计时会话、画布坐标与播放断点）
pub fn init_local_db(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let conn = Connection::open(path)?;
    configure_connection(&conn)?;
    apply_local_schema(&conn)?;
    Ok(conn)
}

fn configure_connection(conn: &Connection) -> Result<()> {
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "synchronous", "NORMAL")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    conn.busy_timeout(std::time::Duration::from_millis(5000))?;
    Ok(())
}

pub fn apply_sync_schema(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS watch_statuses (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            color INTEGER NOT NULL,
            sortOrder INTEGER NOT NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_watch_statuses_name ON watch_statuses (name);

        CREATE TABLE IF NOT EXISTS series (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            customCoverUrl TEXT,
            createdAt TEXT,
            linkedMediaJson TEXT
        );

        CREATE TABLE IF NOT EXISTS animes (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL,
            coverUrl TEXT,
            status TEXT NOT NULL DEFAULT '未看',
            rating INTEGER,
            ratingGrade TEXT,
            funRatingTier TEXT,
            review TEXT,
            seriesId INTEGER,
            createdAt TEXT,
            airDate TEXT,
            studio TEXT,
            watchStartDate TEXT,
            watchFinishDate TEXT,
            watchedEpisodes INTEGER NOT NULL DEFAULT 0,
            totalEpisodes INTEGER NOT NULL DEFAULT 0,
            tvEpisodes INTEGER NOT NULL DEFAULT 0,
            spEpisodes INTEGER NOT NULL DEFAULT 0,
            subjectType TEXT NOT NULL DEFAULT 'anime',
            reminderDay INTEGER,
            reminderTime TEXT,
            deletedAt TEXT,
            externalSource TEXT,
            externalId TEXT,
            externalUrl TEXT,
            originalTitle TEXT,
            synopsis TEXT,
            mediaFormat TEXT,
            pinnedAt INTEGER,
            broadcastDay INTEGER,
            broadcastTime TEXT,
            mergedSourcesJson TEXT,
            voiceActors TEXT,
            coverOrientation TEXT,
            statusChangedAt INTEGER,
            sourceRatingJson TEXT,
            bookReadVolumes INTEGER,
            bookReadChapters INTEGER,
            seriesRole TEXT,
            memo TEXT,
            updatedAt INTEGER,
            FOREIGN KEY (seriesId) REFERENCES series(id) ON DELETE SET NULL
        );

        CREATE TABLE IF NOT EXISTS tags (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            color INTEGER,
            blocked INTEGER NOT NULL DEFAULT 0,
            sortOrder INTEGER
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags (name);

        CREATE TABLE IF NOT EXISTS anime_tags (
            animeId INTEGER NOT NULL,
            tagId INTEGER NOT NULL,
            PRIMARY KEY (animeId, tagId),
            FOREIGN KEY (animeId) REFERENCES animes(id) ON DELETE CASCADE,
            FOREIGN KEY (tagId) REFERENCES tags(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_anime_tags_tagId ON anime_tags (tagId);

        CREATE TABLE IF NOT EXISTS watch_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            animeId INTEGER NOT NULL,
            episode INTEGER NOT NULL,
            status TEXT,
            recordDate TEXT,
            FOREIGN KEY (animeId) REFERENCES animes(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_watch_records_animeId ON watch_records (animeId);

        CREATE TABLE IF NOT EXISTS episode_details (
            animeId INTEGER NOT NULL,
            episode INTEGER NOT NULL,
            title TEXT,
            durationMinutes INTEGER,
            synopsis TEXT,
            review TEXT,
            rating INTEGER,
            updatedAt TEXT,
            PRIMARY KEY (animeId, episode)
        );

        CREATE TABLE IF NOT EXISTS book_reading_states (
            animeId INTEGER PRIMARY KEY NOT NULL,
            localFileUri TEXT,
            localFileName TEXT,
            fileFormat TEXT,
            fileFingerprint TEXT,
            charsetName TEXT,
            chapterIndex INTEGER NOT NULL DEFAULT 0,
            chapterTitle TEXT,
            totalChapters INTEGER NOT NULL DEFAULT 0,
            blockIndex INTEGER NOT NULL DEFAULT 0,
            charOffset INTEGER NOT NULL DEFAULT 0,
            progressPermille INTEGER NOT NULL DEFAULT 0,
            lastReadAt TEXT,
            legadoBookUrl TEXT,
            legadoOriginName TEXT,
            legadoAuthor TEXT,
            legadoChapterIndex INTEGER,
            legadoChapterTitle TEXT,
            legadoTotalChapters INTEGER,
            legadoLastReadAt TEXT,
            syncProgressToMain INTEGER NOT NULL DEFAULT 1,
            updatedAt TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS index_book_reading_states_legadoBookUrl ON book_reading_states (legadoBookUrl);
        CREATE INDEX IF NOT EXISTS index_book_reading_states_lastReadAt ON book_reading_states (lastReadAt);

        CREATE TABLE IF NOT EXISTS book_bookmarks (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            animeId INTEGER NOT NULL,
            chapterIndex INTEGER NOT NULL DEFAULT 0,
            chapterTitle TEXT NOT NULL DEFAULT '',
            blockIndex INTEGER NOT NULL DEFAULT 0,
            charOffset INTEGER NOT NULL DEFAULT 0,
            snippet TEXT NOT NULL DEFAULT '',
            note TEXT,
            createdAt TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS index_book_bookmarks_animeId ON book_bookmarks (animeId);

        CREATE TABLE IF NOT EXISTS anime_analysis_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            serverRecordId INTEGER,
            userId INTEGER,
            username TEXT,
            model TEXT,
            analysis TEXT,
            statsJson TEXT,
            createdAt TEXT
        );

        CREATE TABLE IF NOT EXISTS characters (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            bgmId INTEGER,
            anilistId INTEGER,
            name TEXT NOT NULL,
            nameCn TEXT,
            imageUrl TEXT,
            imageDisplayMode TEXT,
            summary TEXT,
            gender TEXT,
            birthYear INTEGER,
            birthMonth INTEGER,
            birthDay INTEGER,
            bloodType TEXT,
            infoboxJson TEXT,
            rating INTEGER,
            review TEXT,
            updatedAt TEXT
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_characters_bgmId ON characters (bgmId);

        CREATE TABLE IF NOT EXISTS anime_characters (
            animeId INTEGER NOT NULL,
            characterId INTEGER NOT NULL,
            roleName TEXT,
            voiceActor TEXT,
            sortOrder INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (animeId, characterId),
            FOREIGN KEY (animeId) REFERENCES animes(id) ON DELETE CASCADE,
            FOREIGN KEY (characterId) REFERENCES characters(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_anime_characters_characterId ON anime_characters (characterId);

        CREATE TABLE IF NOT EXISTS character_relations (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            sourceCharacterId INTEGER NOT NULL,
            targetCharacterId INTEGER NOT NULL,
            relationType TEXT NOT NULL DEFAULT '关联',
            sourceRole TEXT,
            targetRole TEXT,
            note TEXT,
            strength INTEGER NOT NULL DEFAULT 3,
            createdAt TEXT,
            updatedAt TEXT,
            FOREIGN KEY (sourceCharacterId) REFERENCES characters(id) ON DELETE CASCADE,
            FOREIGN KEY (targetCharacterId) REFERENCES characters(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_character_relations_sourceCharacterId ON character_relations (sourceCharacterId);
        CREATE INDEX IF NOT EXISTS index_character_relations_targetCharacterId ON character_relations (targetCharacterId);

        CREATE TABLE IF NOT EXISTS character_tags (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            color INTEGER,
            createdAt TEXT
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_character_tags_name ON character_tags (name);

        CREATE TABLE IF NOT EXISTS character_tag_links (
            characterId INTEGER NOT NULL,
            tagId INTEGER NOT NULL,
            PRIMARY KEY (characterId, tagId),
            FOREIGN KEY (characterId) REFERENCES characters(id) ON DELETE CASCADE,
            FOREIGN KEY (tagId) REFERENCES character_tags(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_character_tag_links_tagId ON character_tag_links (tagId);

        CREATE TABLE IF NOT EXISTS character_groups (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            coverUrl TEXT,
            communityId TEXT,
            shareCode TEXT,
            source TEXT NOT NULL DEFAULT 'local',
            isPublic INTEGER NOT NULL DEFAULT 0,
            extraJson TEXT,
            createdAt TEXT,
            updatedAt TEXT
        );

        CREATE TABLE IF NOT EXISTS character_group_characters (
            groupId INTEGER NOT NULL,
            characterId INTEGER NOT NULL,
            roleName TEXT,
            sortOrder INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (groupId, characterId),
            FOREIGN KEY (groupId) REFERENCES character_groups(id) ON DELETE CASCADE,
            FOREIGN KEY (characterId) REFERENCES characters(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_character_group_characters_characterId ON character_group_characters (characterId);

        CREATE TABLE IF NOT EXISTS character_group_works (
            groupId INTEGER NOT NULL,
            animeId INTEGER NOT NULL,
            sortOrder INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (groupId, animeId),
            FOREIGN KEY (groupId) REFERENCES character_groups(id) ON DELETE CASCADE,
            FOREIGN KEY (animeId) REFERENCES animes(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_character_group_works_animeId ON character_group_works (animeId);

        CREATE TABLE IF NOT EXISTS games (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            syncId TEXT NOT NULL,
            bangumiId INTEGER,
            vndbId TEXT,
            title TEXT NOT NULL DEFAULT '',
            originalTitle TEXT NOT NULL DEFAULT '',
            coverUrl TEXT NOT NULL DEFAULT '',
            synopsis TEXT NOT NULL DEFAULT '',
            releaseDate TEXT NOT NULL DEFAULT '',
            platforms TEXT NOT NULL DEFAULT '',
            developer TEXT NOT NULL DEFAULT '',
            publisher TEXT NOT NULL DEFAULT '',
            genre TEXT NOT NULL DEFAULT '',
            score REAL NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT '想玩',
            playMinutes INTEGER NOT NULL DEFAULT 0,
            selectedPlatform TEXT NOT NULL DEFAULT '',
            startedOn TEXT NOT NULL DEFAULT '',
            finishedOn TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT '',
            rating INTEGER NOT NULL DEFAULT 0,
            updatedAt TEXT NOT NULL,
            deletedAt TEXT,
            tags TEXT NOT NULL DEFAULT ''
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_games_syncId ON games(syncId);
        CREATE UNIQUE INDEX IF NOT EXISTS index_games_bangumiId ON games(bangumiId);
        CREATE UNIQUE INDEX IF NOT EXISTS index_games_vndbId ON games(vndbId);

        CREATE TABLE IF NOT EXISTS audio_works (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            syncId TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            coverUrl TEXT NOT NULL DEFAULT '',
            category TEXT NOT NULL DEFAULT '其他',
            voiceActors TEXT NOT NULL DEFAULT '',
            circle TEXT NOT NULL DEFAULT '',
            releaseDate TEXT NOT NULL DEFAULT '',
            durationSeconds INTEGER NOT NULL DEFAULT 0,
            workCode TEXT NOT NULL DEFAULT '',
            sourceUrl TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT '想听',
            listenedSeconds INTEGER NOT NULL DEFAULT 0,
            rating INTEGER NOT NULL DEFAULT 0,
            review TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '',
            updatedAt TEXT NOT NULL,
            deletedAt TEXT,
            originalTitle TEXT NOT NULL DEFAULT ''
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_audio_works_syncId ON audio_works(syncId);

        CREATE TABLE IF NOT EXISTS media_series (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            syncId TEXT NOT NULL,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            updatedAt TEXT NOT NULL,
            deletedAt TEXT
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_media_series_syncId ON media_series(syncId);

        CREATE TABLE IF NOT EXISTS media_series_members (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            kind TEXT NOT NULL,
            workSyncId TEXT NOT NULL,
            seriesId INTEGER,
            sortOrder INTEGER NOT NULL,
            updatedAt TEXT NOT NULL,
            FOREIGN KEY(seriesId) REFERENCES media_series(id) ON UPDATE NO ACTION ON DELETE SET NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS index_media_series_members_kind_workSyncId ON media_series_members(kind, workSyncId);
        CREATE INDEX IF NOT EXISTS index_media_series_members_seriesId ON media_series_members(seriesId);

        CREATE TABLE IF NOT EXISTS app_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            level TEXT NOT NULL,
            message TEXT NOT NULL,
            stack_trace TEXT,
            timestamp TEXT NOT NULL
        );
        "#,
    )?;

    // 兼容旧版 Electron 库增量列补齐（幂等；须在创建依赖演进列的索引前完成）
    let incremental_columns: &[(&str, &str, &str)] = &[
        ("tags", "blocked", "INTEGER NOT NULL DEFAULT 0"),
        ("tags", "sortOrder", "INTEGER"),
        ("animes", "coverUrl", "TEXT"),
        ("animes", "status", "TEXT NOT NULL DEFAULT '未看'"),
        ("animes", "rating", "INTEGER"),
        ("animes", "ratingGrade", "TEXT"),
        ("animes", "funRatingTier", "TEXT"),
        ("animes", "review", "TEXT"),
        ("animes", "seriesId", "INTEGER"),
        ("animes", "createdAt", "TEXT"),
        ("animes", "airDate", "TEXT"),
        ("animes", "studio", "TEXT"),
        ("animes", "watchStartDate", "TEXT"),
        ("animes", "watchFinishDate", "TEXT"),
        ("animes", "watchedEpisodes", "INTEGER NOT NULL DEFAULT 0"),
        ("animes", "totalEpisodes", "INTEGER NOT NULL DEFAULT 0"),
        ("animes", "tvEpisodes", "INTEGER NOT NULL DEFAULT 0"),
        ("animes", "spEpisodes", "INTEGER NOT NULL DEFAULT 0"),
        ("animes", "subjectType", "TEXT NOT NULL DEFAULT 'anime'"),
        ("animes", "reminderDay", "INTEGER"),
        ("animes", "reminderTime", "TEXT"),
        ("animes", "deletedAt", "TEXT"),
        ("animes", "externalSource", "TEXT"),
        ("animes", "externalId", "TEXT"),
        ("animes", "externalUrl", "TEXT"),
        ("animes", "originalTitle", "TEXT"),
        ("animes", "synopsis", "TEXT"),
        ("animes", "mediaFormat", "TEXT"),
        ("animes", "pinnedAt", "INTEGER"),
        ("animes", "broadcastDay", "INTEGER"),
        ("animes", "broadcastTime", "TEXT"),
        ("animes", "mergedSourcesJson", "TEXT"),
        ("animes", "voiceActors", "TEXT"),
        ("animes", "coverOrientation", "TEXT"),
        ("animes", "statusChangedAt", "INTEGER"),
        ("animes", "sourceRatingJson", "TEXT"),
        ("animes", "bookReadVolumes", "INTEGER"),
        ("animes", "bookReadChapters", "INTEGER"),
        ("animes", "seriesRole", "TEXT"),
        ("animes", "memo", "TEXT"),
        ("animes", "updatedAt", "INTEGER"),
        ("series", "linkedMediaJson", "TEXT"),
        ("characters", "anilistId", "INTEGER"),
        ("characters", "imageDisplayMode", "TEXT"),
        ("anime_characters", "voiceActor", "TEXT"),
        ("character_relations", "sourceRole", "TEXT"),
        ("character_relations", "targetRole", "TEXT"),
        ("games", "tags", "TEXT NOT NULL DEFAULT ''"),
        ("audio_works", "originalTitle", "TEXT NOT NULL DEFAULT ''"),
    ];

    for (table, col, col_def) in incremental_columns {
        ensure_column(conn, table, col, col_def)?;
    }

    conn.execute_batch(
        r#"
        CREATE INDEX IF NOT EXISTS index_animes_seriesId ON animes (seriesId);
        CREATE INDEX IF NOT EXISTS index_animes_title ON animes (title);
        CREATE INDEX IF NOT EXISTS index_animes_deletedAt ON animes (deletedAt);
        CREATE UNIQUE INDEX IF NOT EXISTS index_animes_externalSource_externalId
            ON animes (externalSource, externalId);
        "#,
    )?;

    let status_count: i64 = conn.query_row("SELECT COUNT(*) FROM watch_statuses", [], |r| r.get(0))?;
    if status_count == 0 {
        let defaults = [
            ("在看", 4280391411_i64, 0_i64),
            ("看完", 4283215696_i64, 1_i64),
            ("未看", 4294936576_i64, 2_i64),
            ("弃坑", 4294198070_i64, 3_i64),
        ];
        for (name, color, order) in defaults {
            conn.execute(
                "INSERT OR IGNORE INTO watch_statuses (name, color, sortOrder) VALUES (?1, ?2, ?3)",
                rusqlite::params![name, color, order],
            )?;
        }
    }

    Ok(())
}

fn apply_local_schema(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS vault_roots (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            path TEXT NOT NULL UNIQUE,
            media_kind TEXT NOT NULL DEFAULT 'mixed',
            label TEXT NOT NULL DEFAULT '',
            enabled INTEGER NOT NULL DEFAULT 1,
            last_scanned_at TEXT,
            file_count INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS vault_files (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            root_id INTEGER,
            file_path TEXT NOT NULL UNIQUE,
            file_name TEXT NOT NULL,
            extension TEXT NOT NULL,
            size_bytes INTEGER NOT NULL DEFAULT 0,
            parsed_title TEXT,
            parsed_episode INTEGER,
            parsed_rj_code TEXT,
            bound_kind TEXT,
            bound_work_id INTEGER,
            subtitle_path TEXT,
            script_path TEXT,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (root_id) REFERENCES vault_roots(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_vault_files_bound ON vault_files(bound_kind, bound_work_id);

        CREATE TABLE IF NOT EXISTS play_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            work_kind TEXT NOT NULL,
            work_id INTEGER NOT NULL,
            launcher_path TEXT,
            started_at TEXT NOT NULL,
            ended_at TEXT NOT NULL,
            duration_seconds INTEGER NOT NULL DEFAULT 0,
            note TEXT
        );

        CREATE TABLE IF NOT EXISTS local_playback_progress (
            file_path TEXT PRIMARY KEY NOT NULL,
            position_seconds REAL NOT NULL DEFAULT 0,
            duration_seconds REAL NOT NULL DEFAULT 0,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS canvas_layouts (
            canvas_key TEXT PRIMARY KEY NOT NULL,
            payload_json TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS local_captures (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            work_kind TEXT NOT NULL,
            work_id INTEGER NOT NULL,
            work_title TEXT NOT NULL,
            image_rel_path TEXT,
            note TEXT NOT NULL DEFAULT '',
            episode_hint INTEGER,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_local_captures_work ON local_captures(work_kind, work_id);

        CREATE TABLE IF NOT EXISTS game_launchers (
            work_id INTEGER PRIMARY KEY NOT NULL,
            exe_path TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS tier_list_entries (
            work_kind TEXT NOT NULL,
            work_id INTEGER NOT NULL,
            tier TEXT NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (work_kind, work_id)
        );

        CREATE TABLE IF NOT EXISTS agent_profiles (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            provider_id TEXT NOT NULL,
            base_url TEXT NOT NULL,
            model TEXT NOT NULL,
            temperature REAL NOT NULL DEFAULT 0.4,
            max_steps INTEGER NOT NULL DEFAULT 8,
            max_turn_tokens INTEGER NOT NULL DEFAULT 60000,
            supports_tools INTEGER NOT NULL DEFAULT 1,
            supports_reasoning INTEGER NOT NULL DEFAULT 0,
            enable_web_search INTEGER NOT NULL DEFAULT 0,
            last_probe_json TEXT,
            is_default INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS agent_sessions (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            profile_id TEXT,
            persona_id TEXT NOT NULL DEFAULT 'curator',
            pinned INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0,
            rolling_summary TEXT,
            compacted_up_to_msg_id TEXT,
            total_prompt_tokens INTEGER NOT NULL DEFAULT 0,
            total_completion_tokens INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS agent_messages (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL DEFAULT '',
            reasoning_content TEXT,
            tool_calls_json TEXT,
            tool_call_id TEXT,
            tool_name TEXT,
            tool_traces_json TEXT,
            ui_actions_json TEXT,
            settings_undo_json TEXT,
            memory_events_json TEXT,
            proposal_id TEXT,
            diagnostic_error_json TEXT,
            token_usage_json TEXT,
            created_at TEXT NOT NULL,
            FOREIGN KEY (session_id) REFERENCES agent_sessions(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_agent_messages_session ON agent_messages(session_id, created_at);

        CREATE TABLE IF NOT EXISTS agent_proposals (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            summary TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            ops_json TEXT NOT NULL,
            diff_preview_json TEXT NOT NULL,
            rollback_snapshot_json TEXT,
            created_at TEXT NOT NULL,
            applied_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_agent_proposals_session ON agent_proposals(session_id, created_at);

        CREATE TABLE IF NOT EXISTS agent_proposal_transitions (
            id TEXT PRIMARY KEY NOT NULL,
            proposal_id TEXT NOT NULL UNIQUE REFERENCES agent_proposals(id) ON DELETE CASCADE,
            status TEXT NOT NULL,
            applied_at TEXT,
            rollback_snapshot_json TEXT
        );

        CREATE TABLE IF NOT EXISTS agent_user_memories (
            id TEXT PRIMARY KEY NOT NULL,
            category TEXT NOT NULL DEFAULT 'preference',
            content TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS agent_slash_commands (
            id TEXT PRIMARY KEY NOT NULL,
            command TEXT NOT NULL UNIQUE,
            label TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            prompt_template TEXT NOT NULL,
            tool_scope_json TEXT,
            is_builtin INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS agent_kv (
            k TEXT PRIMARY KEY NOT NULL,
            v TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS agent_tool_artifacts (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL,
            tool_name TEXT NOT NULL,
            char_len INTEGER NOT NULL,
            full_content TEXT NOT NULL,
            preview TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_agent_tool_artifacts_session ON agent_tool_artifacts(session_id, created_at);

        CREATE TABLE IF NOT EXISTS agent_mcp_servers (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL UNIQUE,
            transport TEXT NOT NULL DEFAULT 'http',
            endpoint_or_cmd TEXT NOT NULL,
            args_json TEXT NOT NULL DEFAULT '[]',
            headers_json TEXT NOT NULL DEFAULT '{}',
            enabled INTEGER NOT NULL DEFAULT 1,
            disabled_tools_json TEXT NOT NULL DEFAULT '[]',
            require_confirm_tools_json TEXT NOT NULL DEFAULT '[]',
            last_status TEXT,
            last_latency_ms INTEGER,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS agent_run_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            run_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            step INTEGER NOT NULL DEFAULT 0,
            event_type TEXT NOT NULL,
            title TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'ok',
            duration_ms INTEGER NOT NULL DEFAULT 0,
            payload_json TEXT NOT NULL DEFAULT '{}',
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_agent_run_events_session ON agent_run_events(session_id, id);
        CREATE INDEX IF NOT EXISTS idx_agent_run_events_run ON agent_run_events(run_id, id);

        CREATE TABLE IF NOT EXISTS agent_verify_jobs (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL,
            result_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'running',
            total_count INTEGER NOT NULL DEFAULT 0,
            processed_count INTEGER NOT NULL DEFAULT 0,
            exact_count INTEGER NOT NULL DEFAULT 0,
            probable_count INTEGER NOT NULL DEFAULT 0,
            no_match_count INTEGER NOT NULL DEFAULT 0,
            failed_count INTEGER NOT NULL DEFAULT 0,
            verify_ids_json TEXT NOT NULL DEFAULT '[]',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_agent_verify_jobs_session ON agent_verify_jobs(session_id, updated_at);

        CREATE TABLE IF NOT EXISTS agent_verify_job_items (
            job_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            work_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            original_title TEXT NOT NULL DEFAULT '',
            local_meta_json TEXT NOT NULL DEFAULT '{}',
            status TEXT NOT NULL DEFAULT 'pending',
            match_type TEXT,
            confidence REAL NOT NULL DEFAULT 0,
            chosen_external_id TEXT,
            chosen_original_title TEXT,
            user_decision TEXT,
            candidates_json TEXT NOT NULL DEFAULT '[]',
            error_message TEXT,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (job_id, kind, work_id),
            FOREIGN KEY (job_id) REFERENCES agent_verify_jobs(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_agent_verify_job_items_status ON agent_verify_job_items(job_id, status);

        CREATE TABLE IF NOT EXISTS agent_verify_rejections (
            kind TEXT NOT NULL,
            work_id INTEGER NOT NULL,
            source TEXT NOT NULL DEFAULT 'bangumi',
            external_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY (kind, work_id, source, external_id)
        );

        CREATE TABLE IF NOT EXISTS work_field_sources (
            kind TEXT NOT NULL,
            work_id INTEGER NOT NULL,
            field_name TEXT NOT NULL,
            source TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (kind, work_id, field_name)
        );

        CREATE TABLE IF NOT EXISTS remote_cover_probe_cache (
            url TEXT PRIMARY KEY NOT NULL,
            available INTEGER NOT NULL,
            checked_at TEXT NOT NULL
        );
        "#,
    )?;
    ensure_column(conn, "agent_profiles", "enable_web_search", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column(conn, "agent_sessions", "pinned", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column(conn, "agent_sessions", "archived", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column(conn, "agent_sessions", "rolling_summary", "TEXT")?;
    ensure_column(conn, "agent_sessions", "compacted_up_to_msg_id", "TEXT")?;
    ensure_column(conn, "agent_messages", "memory_events_json", "TEXT")?;
    ensure_column(conn, "agent_messages", "proposal_ids_json", "TEXT")?;
    ensure_column(conn, "agent_messages", "versions_json", "TEXT")?;
    ensure_column(conn, "agent_messages", "active_version_idx", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column(conn, "agent_messages", "runtime_context", "TEXT")?;
    ensure_column(conn, "agent_messages", "stopped", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column(conn, "agent_messages", "web_sources_json", "TEXT")?;
    ensure_column(conn, "agent_proposals", "applied_count", "INTEGER")?;
    ensure_column(conn, "agent_proposals", "applied_indices_json", "TEXT")?;
    ensure_column(conn, "agent_proposals", "last_attempt_failed", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column(conn, "agent_proposals", "last_error", "TEXT")?;
    ensure_column(conn, "agent_proposals", "batch_group_id", "TEXT")?;
    ensure_column(conn, "agent_proposals", "overlap_warnings_json", "TEXT")?;
    ensure_column(conn, "agent_proposal_transitions", "applied_count", "INTEGER")?;
    ensure_column(conn, "agent_proposal_transitions", "applied_indices_json", "TEXT")?;
    ensure_column(conn, "agent_user_memories", "tier", "TEXT NOT NULL DEFAULT 'core'")?;
    ensure_column(conn, "agent_slash_commands", "skill_guide", "TEXT")?;
    Ok(())
}

pub fn table_columns(conn: &Connection, table: &str) -> Result<HashSet<String>> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info(`{table}`)"))?;
    let rows = stmt.query_map([], |row| row.get::<_, String>(1))?;
    let mut cols = HashSet::new();
    for name in rows {
        cols.insert(name?);
    }
    Ok(cols)
}

fn ensure_column(conn: &Connection, table: &str, column: &str, definition: &str) -> Result<()> {
    let existing = table_columns(conn, table)?;
    if !existing.contains(column) {
        conn.execute(
            &format!("ALTER TABLE `{table}` ADD COLUMN `{column}` {definition}"),
            [],
        )?;
    }
    Ok(())
}
