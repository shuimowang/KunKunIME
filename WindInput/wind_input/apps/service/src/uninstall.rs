//! Windows 卸载向导由独立进程显示，以便最后删除本程序自身。
use crate::cli_util::{RpcFailure, rpc};
use anyhow::{Context, Result, ensure};
use serde_json::{Value, json};
use std::io::Write;
use std::os::windows::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};

const WIZARD: &str = include_str!("../../../../../scripts/uninstall-windows.ps1");
const OWNED_PATHS: &str = include_str!(concat!(env!("OUT_DIR"), "/uninstall-owned-paths.txt"));
const OWNED_HASHES: &str = include_str!(concat!(env!("OUT_DIR"), "/uninstall-owned-sha256.txt"));
const ALTERNATE_HASHES: &str = include_str!(concat!(
    env!("OUT_DIR"),
    "/uninstall-owned-alternate-sha256.txt"
));
static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);

pub fn stop_requested() -> bool {
    STOP_REQUESTED.load(Ordering::Acquire)
}

pub fn stop_matching_service(params: &Value) -> Result<Value> {
    let requested = params["executable"].as_str().context("缺少卸载目标")?;
    if std::fs::canonicalize(requested)? != std::fs::canonicalize(std::env::current_exe()?)? {
        return Ok(json!({"stopping": false}));
    }
    // 给控制管道留出返回时间，再走服务已有的保存词库和退出路径。
    STOP_REQUESTED.store(true, Ordering::Release);
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_millis(200));
        wind_coordinator::request_restart();
    });
    Ok(json!({"stopping": true}))
}

fn dispatch(args: &[String]) -> Result<()> {
    let exe = std::env::current_exe()?;
    if matches!(args, [s] if s == "--stop-service") {
        let info = match rpc("system.info", json!({})) {
            Ok(info) => info,
            Err(RpcFailure::Offline) => return Ok(()),
            Err(RpcFailure::Remote(e)) => return Err(e),
        };
        // An older registered installation may own the singleton while a portable copy is removed.
        let data_dir = Path::new(
            info["dataDir"]
                .as_str()
                .context("无法确定运行中的服务目录")?,
        );
        let local_data = exe.parent().context("缺少安装目录")?.join("data");
        if data_dir != local_data
            && std::fs::canonicalize(data_dir)
                .ok()
                .zip(std::fs::canonicalize(&local_data).ok())
                .is_none_or(|(running, local)| running != local)
        {
            return Ok(());
        }
        match rpc("system.stopForUninstall", json!({"executable": exe})) {
            Ok(_) | Err(RpcFailure::Offline) => return Ok(()),
            Err(RpcFailure::Remote(e)) => return Err(e),
        }
    }
    ensure!(args.is_empty(), "用法：KunKunIME.exe system uninstall");
    let shell = Path::new(&std::env::var_os("SystemRoot").context("无法定位 Windows")?)
        .join(r"System32\WindowsPowerShell\v1.0\powershell.exe");
    let mut helper = Command::new(shell)
        .args(["-NoProfile", "-NonInteractive", "-STA", "-Command",
            "[Console]::InputEncoding=[Text.UTF8Encoding]::new($false); & ([ScriptBlock]::Create([Console]::In.ReadToEnd()))"])
        .env("KUNKUN_UNINSTALL_EXE", &exe)
        .env_remove("PSModulePath")
        .current_dir(std::env::temp_dir())
        .creation_flags(0x08000000)
        .stdin(Stdio::piped())
        .spawn().context("无法打开 Windows 卸载向导")?;
    let mut input = helper.stdin.take().context("无法传送卸载向导")?;
    // Supply build-time file identities separately from the local package manifest.
    writeln!(input, "$packagePaths = @'\n{OWNED_PATHS}\n'@")?;
    writeln!(
        input,
        "$packageHashes = @'\n{OWNED_HASHES}\n{ALTERNATE_HASHES}\n'@"
    )?;
    input.write_all(WIZARD.as_bytes())?;
    Ok(())
}

pub fn run(args: &[String]) -> i32 {
    match dispatch(args) {
        Ok(()) => 0,
        Err(error) => {
            eprintln!("{error:#}");
            1
        }
    }
}
