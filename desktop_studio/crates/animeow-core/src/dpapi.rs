use crate::{Result, StudioError};
use base64::engine::general_purpose::{STANDARD as BASE64_STD, URL_SAFE_NO_PAD};
use base64::Engine;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::Path;
use uuid::Uuid;

const DPAPI_PREFIX: &str = "dpapi:";
const DPAPI_ENTROPY: &[u8] = b"AniMeowStudio.DPAPI.v1";
const SESSION_FILE_NAME: &str = "cloud_session.dpapi.json";
const DEVICE_FILE_NAME: &str = "device_id.json";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PublicBangumiProfile {
    pub id: i64,
    pub username: String,
    pub nickname: Option<String>,
    pub avatar: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredCloudSession {
    pub user_id: i64,
    pub username: String,
    pub token: String,
    pub expires_at: i64,
    pub bangumi: Option<PublicBangumiProfile>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicCloudSessionInfo {
    pub logged_in: bool,
    pub user_id: Option<i64>,
    pub username: Option<String>,
    pub expires_at: Option<i64>,
    pub bangumi: Option<PublicBangumiProfile>,
}

impl PublicCloudSessionInfo {
    pub fn logged_out() -> Self {
        Self {
            logged_in: false,
            user_id: None,
            username: None,
            expires_at: None,
            bangumi: None,
        }
    }

    pub fn from_stored(session: &StoredCloudSession) -> Self {
        Self {
            logged_in: true,
            user_id: Some(session.user_id),
            username: Some(session.username.clone()),
            expires_at: Some(session.expires_at),
            bangumi: session.bangumi.clone(),
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DiskSessionEnvelope {
    user_id: i64,
    username: String,
    token_enc: String,
    expires_at: i64,
    bangumi: Option<PublicBangumiProfile>,
}

#[cfg(target_os = "windows")]
mod win_dpapi {
    use super::{DPAPI_ENTROPY, Result, StudioError};
    use core::ffi::c_void;
    use std::ptr::{null, null_mut};

    const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;

    #[repr(C)]
    struct DataBlob {
        cb_data: u32,
        pb_data: *mut u8,
    }

    #[link(name = "Crypt32")]
    extern "system" {
        fn CryptProtectData(
            p_data_in: *const DataBlob,
            sz_data_descr: *const u16,
            p_optional_entropy: *const DataBlob,
            pv_reserved: *mut c_void,
            p_prompt_struct: *const c_void,
            dw_flags: u32,
            p_data_out: *mut DataBlob,
        ) -> i32;

        fn CryptUnprotectData(
            p_data_in: *const DataBlob,
            ppsz_data_descr: *mut *mut u16,
            p_optional_entropy: *const DataBlob,
            pv_reserved: *mut c_void,
            p_prompt_struct: *const c_void,
            dw_flags: u32,
            p_data_out: *mut DataBlob,
        ) -> i32;
    }

    #[link(name = "Kernel32")]
    extern "system" {
        fn LocalFree(h_mem: *mut c_void) -> *mut c_void;
    }

    pub fn protect_bytes(plain: &[u8]) -> Result<Vec<u8>> {
        if plain.is_empty() {
            return Err(StudioError::InvalidInput("待加密凭据不能为空".into()));
        }
        let in_blob = DataBlob {
            cb_data: plain.len() as u32,
            pb_data: plain.as_ptr() as *mut u8,
        };
        let entropy_blob = DataBlob {
            cb_data: DPAPI_ENTROPY.len() as u32,
            pb_data: DPAPI_ENTROPY.as_ptr() as *mut u8,
        };
        let mut out_blob = DataBlob {
            cb_data: 0,
            pb_data: null_mut(),
        };

        let ok = unsafe {
            CryptProtectData(
                &in_blob,
                null(),
                &entropy_blob,
                null_mut(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out_blob,
            )
        };
        if ok == 0 || out_blob.pb_data.is_null() || out_blob.cb_data == 0 {
            return Err(StudioError::InvalidInput(
                "Windows DPAPI 加密不可用，已拒绝明文保存账号凭据".into(),
            ));
        }

        let bytes = unsafe {
            std::slice::from_raw_parts(out_blob.pb_data, out_blob.cb_data as usize).to_vec()
        };
        unsafe {
            LocalFree(out_blob.pb_data as *mut c_void);
        }
        Ok(bytes)
    }

    pub fn unprotect_bytes(cipher: &[u8]) -> Result<Vec<u8>> {
        if cipher.is_empty() {
            return Err(StudioError::InvalidInput("加密凭据内容为空".into()));
        }
        let in_blob = DataBlob {
            cb_data: cipher.len() as u32,
            pb_data: cipher.as_ptr() as *mut u8,
        };
        let entropy_blob = DataBlob {
            cb_data: DPAPI_ENTROPY.len() as u32,
            pb_data: DPAPI_ENTROPY.as_ptr() as *mut u8,
        };
        let mut out_blob = DataBlob {
            cb_data: 0,
            pb_data: null_mut(),
        };

        let ok = unsafe {
            CryptUnprotectData(
                &in_blob,
                null_mut(),
                &entropy_blob,
                null_mut(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out_blob,
            )
        };
        if ok == 0 || out_blob.pb_data.is_null() {
            return Err(StudioError::InvalidInput(
                "Windows DPAPI 凭据解密失败，请重新登录账号".into(),
            ));
        }

        let bytes = unsafe {
            std::slice::from_raw_parts(out_blob.pb_data, out_blob.cb_data as usize).to_vec()
        };
        unsafe {
            LocalFree(out_blob.pb_data as *mut c_void);
        }
        Ok(bytes)
    }
}

#[cfg(not(target_os = "windows"))]
mod win_dpapi {
    use super::{Result, StudioError};
    pub fn protect_bytes(_plain: &[u8]) -> Result<Vec<u8>> {
        Err(StudioError::InvalidInput(
            "当前非 Windows 平台未启用 DPAPI 凭据仓".into(),
        ))
    }
    pub fn unprotect_bytes(_cipher: &[u8]) -> Result<Vec<u8>> {
        Err(StudioError::InvalidInput(
            "当前非 Windows 平台未启用 DPAPI 凭据仓".into(),
        ))
    }
}

/// 使用系统 DPAPI 加密会话令牌（WIN-02：绝不提供 `raw:` 明文回退）
pub fn encrypt_secret_dpapi(secret: &str) -> Result<String> {
    let trimmed = secret.trim();
    if trimmed.is_empty() {
        return Err(StudioError::InvalidInput("会话令牌不能为空".into()));
    }
    let cipher = win_dpapi::protect_bytes(trimmed.as_bytes())?;
    Ok(format!("{DPAPI_PREFIX}{}", BASE64_STD.encode(cipher)))
}

/// 解密 DPAPI 保护的令牌；遇到 `raw:` 或被篡改的密文立即拒绝（WIN-02）
pub fn decrypt_secret_dpapi(encoded: &str) -> Result<String> {
    if encoded.starts_with("raw:") {
        return Err(StudioError::InvalidInput(
            "检测到不受保护的旧式明文凭据，已按安全规范拒绝加载，请重新登录".into(),
        ));
    }
    let Some(b64) = encoded.strip_prefix(DPAPI_PREFIX) else {
        return Err(StudioError::InvalidInput("未知的凭据加密信封格式".into()));
    };
    let cipher = BASE64_STD
        .decode(b64.trim())
        .map_err(|_| StudioError::InvalidInput("凭据密文 Base64 损坏".into()))?;
    let plain = win_dpapi::unprotect_bytes(&cipher)?;
    String::from_utf8(plain).map_err(|_| StudioError::InvalidInput("凭据字符编码无效".into()))
}

/// 严格解析 JWT `exp` 字段（WIN-03：格式错误、缺失或非正整数一律视为无效，绝不当作永不过期）
pub fn parse_jwt_exp_strict(token: &str) -> Result<i64> {
    let parts: Vec<&str> = token.trim().split('.').collect();
    if parts.len() != 3 || parts[0].is_empty() || parts[1].is_empty() || parts[2].is_empty() {
        return Err(StudioError::InvalidInput("会话令牌格式无效".into()));
    }
    let payload_bytes = URL_SAFE_NO_PAD
        .decode(parts[1].trim_end_matches('='))
        .map_err(|_| StudioError::InvalidInput("会话令牌载荷解码失败".into()))?;
    let val: serde_json::Value = serde_json::from_slice(&payload_bytes)
        .map_err(|_| StudioError::InvalidInput("会话令牌载荷非合法 JSON".into()))?;
    let exp = val
        .get("exp")
        .and_then(|v| v.as_i64())
        .filter(|&e| e > 0)
        .ok_or_else(|| StudioError::InvalidInput("会话令牌缺少有效的过期时间 (exp)".into()))?;
    Ok(exp)
}

/// 原子保存 DPAPI 加密会话到 `studio_data_dir/cloud_session.dpapi.json`
pub fn save_cloud_session(
    studio_data_dir: &Path,
    user_id: i64,
    username: &str,
    token: &str,
    bangumi: Option<PublicBangumiProfile>,
) -> Result<StoredCloudSession> {
    if user_id <= 0 || username.trim().is_empty() {
        return Err(StudioError::InvalidInput("登录用户信息不完整".into()));
    }
    let exp = parse_jwt_exp_strict(token)?;
    let now = Utc::now().timestamp();
    if exp <= now {
        return Err(StudioError::InvalidInput("登录状态已过期，请重新登录".into()));
    }
    let token_enc = encrypt_secret_dpapi(token)?;

    std::fs::create_dir_all(studio_data_dir)?;
    let target = studio_data_dir.join(SESSION_FILE_NAME);
    let tmp = studio_data_dir.join(format!("{SESSION_FILE_NAME}.{}.tmp", Uuid::new_v4()));

    let envelope = DiskSessionEnvelope {
        user_id,
        username: username.trim().to_string(),
        token_enc,
        expires_at: exp,
        bangumi: bangumi.clone(),
    };
    let serialized = serde_json::to_vec_pretty(&envelope)?;
    if let Err(err) = std::fs::write(&tmp, serialized) {
        let _ = std::fs::remove_file(&tmp);
        return Err(StudioError::Io(err));
    }
    if let Err(err) = std::fs::rename(&tmp, &target) {
        let _ = std::fs::remove_file(&tmp);
        return Err(StudioError::Io(err));
    }

    Ok(StoredCloudSession {
        user_id,
        username: username.trim().to_string(),
        token: token.trim().to_string(),
        expires_at: exp,
        bangumi,
    })
}

/// 读取并校验本地 DPAPI 会话（过期或非法有效期自动判定未登录）
pub fn load_cloud_session(studio_data_dir: &Path) -> Result<Option<StoredCloudSession>> {
    let target = studio_data_dir.join(SESSION_FILE_NAME);
    if !target.exists() {
        return Ok(None);
    }
    let raw = std::fs::read_to_string(&target)?;
    let env: DiskSessionEnvelope = serde_json::from_str(&raw)
        .map_err(|_| StudioError::InvalidInput("本地加密会话文件损坏，请退出后重新登录".into()))?;
    if env.user_id <= 0 || env.username.trim().is_empty() || env.expires_at <= 0 {
        return Ok(None);
    }
    let token = decrypt_secret_dpapi(&env.token_enc)?;
    let jwt_exp = parse_jwt_exp_strict(&token)?;
    let effective_exp = env.expires_at.min(jwt_exp);
    if effective_exp <= Utc::now().timestamp() {
        return Ok(None);
    }
    Ok(Some(StoredCloudSession {
        user_id: env.user_id,
        username: env.username,
        token,
        expires_at: effective_exp,
        bangumi: env.bangumi,
    }))
}

/// 安全退出并销毁本地会话凭据（WIN-03：绝不静默忽略清盘失败）
pub fn clear_cloud_session(studio_data_dir: &Path) -> Result<()> {
    let target = studio_data_dir.join(SESSION_FILE_NAME);
    if !target.exists() {
        return Ok(());
    }
    std::fs::remove_file(&target).map_err(|e| {
        StudioError::InvalidInput(format!("无法清除本地会话凭据文件，请检查磁盘权限：{e}"))
    })?;
    if target.exists() {
        return Err(StudioError::InvalidInput(
            "本地会话凭据文件仍残留，退出未完成".into(),
        ));
    }
    Ok(())
}

/// 获取或生成稳定的 64 位十六进制本机设备登记号（兼容承接旧版 Electron `config.json` 中的 `deviceRegistrationId`）
pub fn ensure_device_registration_id(
    studio_data_dir: &Path,
    appdata_roaming_dir: Option<&Path>,
) -> Result<String> {
    std::fs::create_dir_all(studio_data_dir)?;
    let device_file = studio_data_dir.join(DEVICE_FILE_NAME);
    if device_file.exists() {
        if let Ok(raw) = std::fs::read_to_string(&device_file) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) {
                if let Some(id) = v.get("deviceRegistrationId").and_then(|s| s.as_str()) {
                    if is_valid_hex64(id) {
                        return Ok(id.to_ascii_lowercase());
                    }
                }
            }
        }
    }

    // 尝试只读承接同机旧版 Electron 已登记的 deviceRegistrationId，使“用本机登录”无缝可用
    if let Some(roaming) = appdata_roaming_dir {
        let legacy_cfg = roaming.join("animeow-desktop").join("config.json");
        if legacy_cfg.is_file() {
            if let Ok(raw) = std::fs::read_to_string(&legacy_cfg) {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) {
                    if let Some(id) = v.get("deviceRegistrationId").and_then(|s| s.as_str()) {
                        if is_valid_hex64(id) {
                            let normalized = id.to_ascii_lowercase();
                            let payload =
                                serde_json::json!({ "deviceRegistrationId": normalized });
                            let _ = std::fs::write(
                                &device_file,
                                serde_json::to_vec_pretty(&payload)?,
                            );
                            return Ok(normalized);
                        }
                    }
                }
            }
        }
    }

    let mut hasher = Sha256::new();
    hasher.update(b"animeow-studio:");
    hasher.update(
        std::env::var("COMPUTERNAME")
            .unwrap_or_else(|_| "windows-pc".into())
            .as_bytes(),
    );
    hasher.update(b":");
    hasher.update(studio_data_dir.to_string_lossy().as_bytes());
    hasher.update(b":");
    hasher.update(Uuid::new_v4().as_bytes());
    let hex_id = hex::encode(hasher.finalize());

    let payload = serde_json::json!({ "deviceRegistrationId": hex_id });
    std::fs::write(&device_file, serde_json::to_vec_pretty(&payload)?)?;
    Ok(hex_id)
}

fn is_valid_hex64(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
}
