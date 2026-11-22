//! 从词库管理窗注册当前程序的 Windows 输入法。

use std::cell::RefCell;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::{FromRawHandle, OwnedHandle};
use std::os::windows::process::CommandExt;
use std::path::Path;
use std::process::{Command, Output};
use std::rc::Rc;

use serde::Deserialize;
use windows::{
    Win32::{
        Foundation::{ERROR_CANCELLED, WAIT_OBJECT_0},
        System::{
            Com::{
                COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE, CoInitializeEx, CoUninitialize,
            },
            Threading::{CREATE_NO_WINDOW, GetExitCodeProcess, INFINITE, WaitForSingleObject},
        },
        UI::Shell::{
            SEE_MASK_FLAG_NO_UI, SEE_MASK_NOASYNC, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW,
            ShellExecuteExW,
        },
    },
    core::{PCWSTR, w},
};
use windui::prelude::*;

pub type RegistrationResult = Result<(), String>;

pub enum Reply {
    Status(Result<RegistrationStatus, String>),
    Submitted(RegistrationResult),
}

#[derive(Clone)]
pub struct Registration {
    busy: Signal<bool>,
    complete: Signal<Option<bool>>,
    tx: Rc<RefCell<Option<Sender<Reply>>>>,
}

impl Registration {
    pub fn new() -> Self {
        Self {
            busy: signal(false),
            complete: signal(None),
            tx: Rc::new(RefCell::new(None)),
        }
    }

    pub fn set_tx(&self, tx: Sender<Reply>) {
        *self.tx.borrow_mut() = Some(tx);
    }

    /// 在窗口打开、重新获得焦点或手动刷新时读取系统状态。并发请求合并。
    pub fn refresh(&self) {
        if crate::rpc::is_mock() {
            self.complete.set(Some(true));
            return;
        }
        if self.busy.get() {
            return;
        }
        let Some(tx) = self.tx.borrow().clone() else {
            return;
        };
        self.busy.set(true);
        if let Err(error) = std::thread::Builder::new().spawn(move || {
            let status = crate::mode::core_exe()
                .ok_or_else(|| "找不到困困输入法核心程序".to_string())
                .and_then(|exe| query_status(&exe));
            let _ = tx.send(Reply::Status(status));
        }) {
            let _ = self.on_reply(Reply::Status(Err(error.to_string())));
        }
    }

    pub fn on_reply(&self, reply: Reply) -> Option<RegistrationResult> {
        self.busy.set(false);
        match reply {
            Reply::Status(Ok(status)) => {
                self.complete.set(Some(status.registered && status.enabled));
                None
            }
            Reply::Status(Err(error)) => {
                tracing::debug!("无法读取 Windows 输入法注册状态：{error}");
                if self.complete.get().is_none() {
                    self.complete.set(Some(false));
                }
                None
            }
            Reply::Submitted(result) => {
                if result.is_ok() {
                    self.complete.set(Some(true));
                }
                Some(result)
            }
        }
    }

    pub fn button(&self) -> Element {
        let state = self.clone();
        let busy = self.busy;
        let complete = self.complete;
        Element::button("注册输入法")
            .small()
            .outline_soft()
            .width_match()
            .visible_when(move || complete.get() == Some(false))
            .enabled_when(move || !busy.get())
            .tooltip("将困困输入法注册为 Windows 输入法。需要管理员授权，完成后按 Win + 空格切换。")
            .on_click(move |ctx| {
                if busy.get() {
                    return;
                }
                let Some(tx) = state.tx.borrow().clone() else {
                    return;
                };
                busy.set(true);
                if let Err(error) = std::thread::Builder::new().spawn(move || {
                    let _ = tx.send(Reply::Submitted(register_and_enable()));
                }) {
                    busy.set(false);
                    ctx.toast_err(format!("无法开始输入法注册。{error}"));
                }
            })
    }
}

fn register_and_enable() -> RegistrationResult {
    let exe = crate::mode::core_exe().ok_or("无法定位当前困困输入法程序。")?;
    let status = query_status(&exe)?;
    if !status.registered {
        register_machine(&exe)?;
    } else if status.enabled {
        return Ok(());
    }

    // UAC 可能使用另一个管理员账户。键盘列表从设置程序的当前用户运行 enable，
    // 确保写到原来的 Windows 用户。
    let enabled = run_core(&exe, &["enable"])
        .map_err(|e| format!("系统注册已完成，无法添加到当前用户的输入法列表。{e}"))?;
    if !enabled.status.success() {
        return Err(format!(
            "系统注册已完成，添加到当前用户的输入法列表失败。{}",
            failure_detail(&enabled)
        ));
    }
    let status = query_status(&exe)?;
    if !(status.registered && status.enabled) {
        return Err("Windows 尚未确认当前用户已启用困困输入法。".to_owned());
    }
    Ok(())
}

#[derive(Deserialize)]
pub struct RegistrationStatus {
    registered: bool,
    enabled: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn status(registered: bool, enabled: bool) -> Reply {
        Reply::Status(Ok(RegistrationStatus {
            registered,
            enabled,
        }))
    }

    #[test]
    fn registration_entry_tracks_machine_and_current_user() {
        let state = Registration::new();
        assert_eq!(state.complete.get(), None);
        assert!(state.on_reply(status(true, true)).is_none());
        assert_eq!(state.complete.get(), Some(true));
        assert!(
            state
                .on_reply(Reply::Status(Err("temporary".into())))
                .is_none()
        );
        assert_eq!(state.complete.get(), Some(true));
        assert!(state.on_reply(status(true, false)).is_none());
        assert_eq!(state.complete.get(), Some(false));
        assert!(state.on_reply(status(false, false)).is_none());
        assert_eq!(state.complete.get(), Some(false));
        assert!(state.on_reply(Reply::Submitted(Ok(()))).unwrap().is_ok());
        assert_eq!(state.complete.get(), Some(true));
    }

    #[test]
    fn registration_unknown_and_failed_submission_allow_retry() {
        let state = Registration::new();
        assert!(
            state
                .on_reply(Reply::Status(Err("failed".into())))
                .is_none()
        );
        assert_eq!(state.complete.get(), Some(false));
        state.busy.set(true);
        assert!(
            state
                .on_reply(Reply::Submitted(Err("cancelled".into())))
                .unwrap()
                .is_err()
        );
        assert!(!state.busy.get());
        assert_eq!(state.complete.get(), Some(false));
    }
}

fn query_status(exe: &Path) -> Result<RegistrationStatus, String> {
    let output =
        run_core(exe, &["status", "--json"]).map_err(|e| format!("无法查询输入法注册状态。{e}"))?;
    if !output.status.success() {
        return Err(format!(
            "无法查询输入法注册状态。{}",
            failure_detail(&output)
        ));
    }
    serde_json::from_slice(&output.stdout).map_err(|e| format!("无法读取输入法注册状态。{e}"))
}

fn run_core(exe: &Path, args: &[&str]) -> std::io::Result<Output> {
    Command::new(exe)
        .args(["system", "register-ime"])
        .args(args)
        .env("WIND_INPUT_CLI_PIPED", "1")
        .creation_flags(CREATE_NO_WINDOW.0)
        .output()
}

fn failure_detail(output: &Output) -> String {
    let detail = String::from_utf8_lossy(&output.stderr);
    let detail = detail.trim();
    if detail.is_empty() {
        output.status.to_string()
    } else {
        detail.to_owned()
    }
}

fn register_machine(exe: &Path) -> RegistrationResult {
    let path: Vec<u16> = exe.as_os_str().encode_wide().chain([0]).collect();
    let mut call = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI,
        lpVerb: w!("runas"),
        lpFile: PCWSTR(path.as_ptr()),
        lpParameters: w!("system register-ime"),
        nShow: 0, // SW_HIDE。Windows 的管理员授权仍正常显示。
        ..Default::default()
    };
    // 工作线程有独立 COM apartment。ShellExecuteExW 按返回的句柄报告真实注册结果。
    unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) }
        .ok()
        .map_err(|e| format!("无法初始化 Windows 注册流程。{e}"))?;
    let launch = unsafe { ShellExecuteExW(&mut call) };
    unsafe { CoUninitialize() };
    if let Err(e) = launch {
        return Err(
            if e.code() == windows::core::HRESULT::from_win32(ERROR_CANCELLED.0) {
                "已取消管理员授权。".to_owned()
            } else {
                format!("无法启动输入法注册。{e}")
            },
        );
    }

    if call.hProcess.is_invalid() {
        return Err("Windows 未返回注册进程，无法确认是否成功。".to_owned());
    }
    let _process = unsafe { OwnedHandle::from_raw_handle(call.hProcess.0) };
    if unsafe { WaitForSingleObject(call.hProcess, INFINITE) } != WAIT_OBJECT_0 {
        let error = std::io::Error::last_os_error();
        return Err(format!("无法等待注册结果。{error}"));
    }

    let mut code = 0;
    unsafe { GetExitCodeProcess(call.hProcess, &mut code) }
        .map_err(|e| format!("无法读取注册结果。{e}"))?;
    if code != 0 {
        return Err(format!("Windows 输入法注册失败，错误码 {code}。"));
    }
    Ok(())
}
