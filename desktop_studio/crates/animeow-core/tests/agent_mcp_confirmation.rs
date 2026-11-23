use animeow_core::agent::{
    commit_mcp_call_proposal, create_mcp_call_proposal, create_session, load_proposal,
    reject_proposal, save_mcp_server,
};
use animeow_core::schema::init_local_db;
use axum::{
    body::Body, extract::State, http::StatusCode, response::IntoResponse, routing::post, Json,
    Router,
};
use serde_json::{json, Value};
use std::io;
use std::pin::Pin;
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::io::{AsyncRead, ReadBuf};
use tokio::sync::Notify;
use tokio_util::io::ReaderStream;

struct InterruptedBody;

impl AsyncRead for InterruptedBody {
    fn poll_read(
        self: Pin<&mut Self>,
        _: &mut Context<'_>,
        _: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Poll::Ready(Err(io::ErrorKind::ConnectionReset.into()))
    }
}

#[derive(Default)]
struct ServerState {
    calls: AtomicUsize,
    started: Notify,
    release: Notify,
}

async fn mock_service(State(state): State<Arc<ServerState>>, Json(request): Json<Value>) -> impl IntoResponse {
    let mut result = json!({"protocolVersion": "2025-03-26", "capabilities": {}});
    if request["method"] == "tools/call" {
        state.calls.fetch_add(1, Ordering::SeqCst);
        result = json!({"content": [{"type": "text", "text": "本机测试成功"}]});
        match request["params"]["name"].as_str() {
            Some("hold_write") => {
                state.started.notify_one();
                state.release.notified().await;
            }
            Some("disconnect_write") => return Body::from_stream(ReaderStream::new(InterruptedBody)).into_response(),
            Some("empty_write") => return StatusCode::NO_CONTENT.into_response(),
            Some("failed_write") => result["isError"] = json!(true),
            Some("mismatched_write") => return Json(json!({"jsonrpc":"2.0","id":99,"result":result})).into_response(),
            Some("redirect_write") => return (StatusCode::TEMPORARY_REDIRECT, [("location", "/mcp")]).into_response(),
            _ => {}
        }
    }
    Json(json!({"jsonrpc": "2.0", "id": request["id"], "result": result})).into_response()
}

#[tokio::test]
async fn confirmed_mcp_proposal_dispatches_once_and_never_replays_an_unknown_result() {
    let dir = tempfile::tempdir().unwrap();
    let local_path = dir.path().join("desktop_local.db");
    let local = Arc::new(Mutex::new(init_local_db(&local_path).unwrap()));
    let other_window = Arc::new(Mutex::new(init_local_db(&local_path).unwrap()));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let state = Arc::new(ServerState::default());
    let app = Router::new().route("/mcp", post(mock_service)).with_state(state.clone());
    {
        let local = local.lock().unwrap();
        save_mcp_server(&local, dir.path(), serde_json::from_value(json!({
            "name": "本机测试服务", "transport": "http", "enabled": true,
            "endpointOrCmd": format!("http://{}/mcp", listener.local_addr().unwrap())
        })).unwrap()).unwrap();
    }
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let session = create_session(&local.lock().unwrap(), None, None, None).unwrap();
    let proposal = |name: &str| {
        create_mcp_call_proposal(&local.lock().unwrap(), &session.id, "本机测试服务", name, &json!({})).unwrap()
    };
    let confirm_in_background = |id: String| {
        let db = local.clone();
        let root = dir.path().to_owned();
        tokio::spawn(async move { commit_mcp_call_proposal(&db, &root, &id).await })
    };
    let first = proposal("hold_write");
    let in_flight = confirm_in_background(first.id.clone());
    tokio::time::timeout(Duration::from_secs(5), state.started.notified()).await.unwrap();
    assert!(commit_mcp_call_proposal(&other_window, dir.path(), &first.id).await.is_err());
    assert!(reject_proposal(&other_window.lock().unwrap(), &first.id).is_err());
    assert_eq!(state.calls.load(Ordering::SeqCst), 1);
    state.release.notify_one();
    let result = in_flight.await.unwrap().unwrap();
    assert_eq!(result.proposal.status, "applied");
    assert_eq!(result.applied_count, 1);
    let saved = load_proposal(&other_window.lock().unwrap(), &first.id).unwrap().unwrap().0;
    assert_eq!(saved.status, "applied");
    assert!(commit_mcp_call_proposal(&other_window, dir.path(), &first.id).await.is_err());

    for (index, tool) in ["disconnect_write", "empty_write", "failed_write", "mismatched_write", "redirect_write"].into_iter().enumerate() {
        let pending = proposal(tool);
        let result = commit_mcp_call_proposal(&local, dir.path(), &pending.id).await.unwrap();
        assert_eq!(result.applied_count, 0);
        assert_eq!(result.proposal.status, "unconfirmed");
        assert!(result.proposal.applied_at.is_none());
        let reopened = Arc::new(Mutex::new(init_local_db(&local_path).unwrap()));
        let saved = load_proposal(&reopened.lock().unwrap(), &pending.id).unwrap().unwrap().0;
        assert_eq!(saved.status, "unconfirmed");
        assert!(commit_mcp_call_proposal(&reopened, dir.path(), &pending.id).await.is_err());
        assert_eq!(state.calls.load(Ordering::SeqCst), index + 2);
    }

    let interrupted = proposal("hold_write");
    let in_flight = confirm_in_background(interrupted.id.clone());
    tokio::time::timeout(Duration::from_secs(5), state.started.notified()).await.unwrap();
    in_flight.abort();
    assert!(in_flight.await.unwrap_err().is_cancelled());
    assert!(commit_mcp_call_proposal(&other_window, dir.path(), &interrupted.id).await.is_err());
    assert_eq!(state.calls.load(Ordering::SeqCst), 7);
    state.release.notify_one();
    server.abort();
}
