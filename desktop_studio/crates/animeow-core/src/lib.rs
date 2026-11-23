#![recursion_limit = "256"]

pub mod agent;
pub mod backup;
pub mod cloud;
pub mod companion_ext;
pub mod dpapi;
pub mod legacy;
pub mod media_server;
pub mod merger;
pub mod repository;
pub mod schema;
pub mod studio_features;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum StudioError {
    #[error("数据库操作失败：{0}")]
    Database(#[from] rusqlite::Error),
    #[error("文件读写失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("备份格式错误：{0}")]
    BackupFormat(String),
    #[error("JSON 解析失败：{0}")]
    Json(#[from] serde_json::Error),
    #[error("压缩包处理失败：{0}")]
    Zip(#[from] zip::result::ZipError),
    #[error("{0}")]
    InvalidInput(String),
}

pub type Result<T> = std::result::Result<T, StudioError>;
