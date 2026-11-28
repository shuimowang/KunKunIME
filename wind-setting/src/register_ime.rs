//! Windows 输入法注册与当前账户的默认输入法。

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
    Default(
        DefaultCommand,
        Result<DefaultStatus, String>,
        Option<RegistrationStatus>,
    ),
    Submitted(RegistrationResult),
}

#[derive(Clone)]
pub struct Registration {
    busy: Signal<bool>,
    complete: Signal<Option<bool>>,
    default: Signal<Result<Option<DefaultStatus>, String>>,
    tx: Rc<RefCell<Option<Sender<Reply>>>>,
}

impl Registration {
    pub fn new() -> Self {
        Self {
            busy: signal(false),
            complete: signal(None),
            default: signal(Ok(None)),
            tx: Rc::new(RefCell::new(None)),
        }
    }

    pub fn set_tx(&self, tx: Sender<Reply>) {
        *self.tx.borrow_mut() = Some(tx);
    }

    /// 窗口打开、重新获得焦点和手动刷新只查询系统，共用一个进行中的请求。
    pub fn refresh(&self) {
        self.request(DefaultCommand::Read);
    }

    // 状态读取、注册和显式更改串行，旧读取不会覆盖刚写入的结果。
    fn request(&self, command: DefaultCommand) {
        if self.busy.get() {
            return;
        }
        if command != DefaultCommand::Read
            && !self.default.with(|last| {
                last.as_ref()
                    .ok()
                    .and_then(Option::as_ref)
                    .is_some_and(|status| status.action() == Some(command))
            })
        {
            return;
        }
        if crate::rpc::is_mock() {
            if command == DefaultCommand::Read && self.default.get() != Ok(None) {
                return;
            }
            let mode = if command == DefaultCommand::Set {
                DefaultMode::Kunkun
            } else {
                DefaultMode::Auto
            };
            self.on_reply(Reply::Default(
                command,
                Ok(DefaultStatus {
                    registered: true,
                    enabled: true,
                    mode,
                    name: None,
                    input_tip: None,
                }),
                None,
            ));
            return;
        }
        let Some(tx) = self.tx.borrow().clone() else {
            return;
        };
        self.busy.set(true);
        if let Err(error) = std::thread::Builder::new().spawn(move || {
            let exe = crate::mode::core_exe();
            let result = exe
                .as_deref()
                .ok_or_else(|| "找不到困困输入法核心程序".to_string())
                .and_then(|exe| query_default(exe, command));
            // 默认项不可读时仍单独检查注册，保留原注册按钮的判断。
            let registration = if command == DefaultCommand::Read && result.is_err() {
                exe.as_deref().and_then(|exe| query_status(exe).ok())
            } else {
                None
            };
            let _ = tx.send(Reply::Default(command, result, registration));
        }) {
            self.on_reply(Reply::Default(command, Err(error.to_string()), None));
        }
    }

    pub fn on_reply(&self, reply: Reply) -> Option<Result<&'static str, String>> {
        self.busy.set(false);
        match reply {
            Reply::Default(command, result, fallback) => {
                match &result {
                    Ok(status) => self.complete.set(Some(status.registered && status.enabled)),
                    Err(_) => {
                        if let Some(status) = fallback {
                            self.complete.set(Some(status.registered && status.enabled));
                        }
                    }
                }
                self.default.set(result.clone().map(Some));
                match command {
                    DefaultCommand::Read => None,
                    DefaultCommand::Set => {
                        Some(result.map(|_| "已将困困输入法设为当前 Windows 账户的默认输入法"))
                    }
                    DefaultCommand::Reset => {
                        Some(result.map(|_| "已恢复由 Windows 自动选择默认输入法"))
                    }
                }
            }
            Reply::Submitted(result) => {
                if result.is_ok() {
                    self.complete.set(Some(true));
                    self.refresh();
                }
                Some(result.map(|_| "已注册为 Windows 输入法。按 Win + 空格切换。"))
            }
        }
    }

    pub fn default_row(&self) -> Element {
        let last = self.default;
        let busy = self.busy;
        let summary = last.map(|v| match v {
            Ok(None) => "正在读取当前账户的设置…".to_owned(),
            Ok(Some(s)) => s.summary(),
            Err(e) => format!("无法读取或确认当前设置：{e}"),
        });
        let action = |text: &'static str, command| {
            let this = self.clone();
            Element::button(text)
                .small()
                .outline_soft()
                .visible_when(move || {
                    last.with(|v| {
                        v.as_ref()
                            .ok()
                            .and_then(Option::as_ref)
                            .is_some_and(|s| s.action() == Some(command))
                    })
                })
                .enabled_when(move || !busy.get())
                .on_click(move |_| this.request(command))
        };
        let this = self.clone();
        let buttons = Element::row()
            .spacing(8)
            .cross(Align::Center)
            .child(action("设为默认输入法", DefaultCommand::Set))
            .child(action("恢复自动选择", DefaultCommand::Reset))
            .child(
                Element::button("刷新状态")
                    .small()
                    .outline_soft()
                    .enabled_when(move || !busy.get())
                    .on_click(move |_| this.refresh()),
            );
        Element::col()
            .width_match()
            .padding_xy(16, 10)
            .spacing(5)
            .child(Element::label("Windows 登录默认输入法").font_size(14.0))
            .child(Element::label_signal(summary).width_match().font_size(13.0))
            .child(
                Element::label(
                    "决定当前 Windows 账户登录后优先使用的输入法。仍可按 Win + 空格临时切换。",
                )
                .width_match()
                .font_size(12.0)
                .fg_role(Role::TextMuted),
            )
            .child(buttons)
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DefaultCommand {
    Read,
    Set,
    Reset,
}

impl DefaultCommand {
    fn argument(self) -> &'static str {
        match self {
            Self::Read => "default-status",
            Self::Set => "set-default",
            Self::Reset => "reset-default",
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
enum DefaultMode {
    Auto,
    Kunkun,
    Other,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct DefaultStatus {
    registered: bool,
    enabled: bool,
    mode: DefaultMode,
    name: Option<String>,
    #[serde(rename = "inputTip")]
    input_tip: Option<String>,
}

impl DefaultStatus {
    fn action(&self) -> Option<DefaultCommand> {
        match self.mode {
            DefaultMode::Kunkun => Some(DefaultCommand::Reset),
            _ if self.registered && self.enabled => Some(DefaultCommand::Set),
            _ => None,
        }
    }

    fn summary(&self) -> String {
        let mut s = match self.mode {
            DefaultMode::Auto => "当前：Windows 自动选择".to_owned(),
            DefaultMode::Kunkun => "当前：困困输入法".to_owned(),
            DefaultMode::Other => format!(
                "当前：{}",
                self.name
                    .as_deref()
                    .filter(|s| !s.is_empty())
                    .or(self.input_tip.as_deref())
                    .unwrap_or("其他输入法")
            ),
        };
        if self.mode == DefaultMode::Kunkun && !self.registered {
            s.push_str("。当前程序目录尚未注册；若安装过多份，可从已注册的目录打开。");
        } else if !(self.registered && self.enabled) {
            s.push_str("。要使用困困输入法，请先点击左下角的「注册输入法」。");
        }
        s
    }
}

fn query_default(exe: &Path, command: DefaultCommand) -> Result<DefaultStatus, String> {
    let output = run_core(exe, &[command.argument(), "--json"]).map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(failure_detail(&output));
    }
    let status: DefaultStatus = serde_json::from_slice(&output.stdout)
        .map_err(|e| format!("无法识别 Windows 默认输入法状态。{e}"))?;
    if (command == DefaultCommand::Set && status.mode != DefaultMode::Kunkun)
        || (command == DefaultCommand::Reset && status.mode != DefaultMode::Auto)
    {
        return Err("Windows 回读的默认输入法与操作不一致，请刷新状态。".to_owned());
    }
    Ok(status)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_default_ime_distinguishes_account_default_from_this_copy() {
        let status = DefaultStatus {
            registered: false, enabled: false, mode: DefaultMode::Kunkun,
            name: None, input_tip: None,
        };
        assert!(status.summary().contains("当前程序目录尚未注册"));
        assert!(!status.summary().contains("请先点击"));
    }

    fn status(registered: bool, enabled: bool) -> Reply {
        Reply::Default(
            DefaultCommand::Read,
            Ok(DefaultStatus {
                registered,
                enabled,
                mode: DefaultMode::Auto,
                name: None,
                input_tip: None,
            }),
            None,
        )
    }

    #[test]
    fn windows_default_ime_clicks_change_only_the_requested_default() {
        use windui::core::Tree;
        use windui::event::{MouseButton, PointerEvent, PointerKind};
        use windui::geometry::{Point, Size};
        let _guard = crate::rpc::ENV_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        // The mock backend only changes the in-memory status used by this test.
        unsafe { std::env::set_var("WIND_RPC_MOCK", "1") };
        let reg = Registration::new();
        reg.refresh();
        let mut tree = Tree::new();
        let root = reg.default_row().build(&mut tree);
        tree.root = Some(root);
        let layout = |tree: &mut Tree| {
            tree.layout_root(Size::new(402, 200), &mut windui::text::NullTextEngine)
        };
        layout(&mut tree);
        let bar = *tree.get(root).unwrap().children.last().unwrap();
        let buttons = tree.get(bar).unwrap().children.clone();
        let visible = |tree: &Tree, i: usize| tree.get(buttons[i]).unwrap().effective_visible();
        let click = |i: usize, tree: &mut Tree| {
            let b = tree.abs_bounds(buttons[i]);
            let p = Point::new(b.x + b.w / 2, b.y + b.h / 2);
            let (mut hover, mut capture) = (None, None);
            for kind in [PointerKind::Down, PointerKind::Up] {
                tree.dispatch_pointer(
                    PointerEvent::single(kind, p, MouseButton::Left),
                    &mut hover,
                    &mut capture,
                );
            }
            layout(tree);
        };
        assert!(visible(&tree, 0) && !visible(&tree, 1));
        click(2, &mut tree); // refresh
        assert_eq!(reg.default.get().unwrap().unwrap().mode, DefaultMode::Auto);
        click(0, &mut tree); // set
        assert!(!visible(&tree, 0) && visible(&tree, 1));
        assert_eq!(
            reg.default.get().unwrap().unwrap().mode,
            DefaultMode::Kunkun
        );
        click(2, &mut tree);
        assert_eq!(
            reg.default.get().unwrap().unwrap().mode,
            DefaultMode::Kunkun
        );
        click(1, &mut tree); // reset
        assert_eq!(reg.default.get().unwrap().unwrap().mode, DefaultMode::Auto);
        assert!(visible(&tree, 0) && !visible(&tree, 1));
        unsafe { std::env::remove_var("WIND_RPC_MOCK") };
    }

    #[test]
    fn windows_default_ime_renders_other_unregistered_and_failed_reads() {
        let reg = Registration::new();
        let mut status: DefaultStatus = serde_json::from_str(r#"{"registered":true,"enabled":true,"mode":"other","name":"微软拼音","inputTip":"0804:other"}"#).unwrap();
        assert_eq!(status.action(), Some(DefaultCommand::Set));
        assert!(status.summary().contains("微软拼音"));
        status.enabled = false;
        assert_eq!(status.action(), None);
        assert!(status.summary().contains("注册输入法"));
        reg.on_reply(Reply::Default(
            DefaultCommand::Read,
            Ok(status.clone()),
            None,
        ));
        assert_eq!(reg.complete.get(), Some(false));
        let error = "系统读取失败".to_string();
        reg.on_reply(Reply::Default(
            DefaultCommand::Read,
            Err(error.clone()),
            None,
        ));
        assert_eq!(reg.default.get(), Err(error));
        assert_eq!(reg.complete.get(), Some(false));
    }

    #[test]
    fn registration_entry_tracks_machine_and_current_user() {
        let state = Registration::new();
        assert_eq!(state.complete.get(), None);
        assert!(state.on_reply(status(true, true)).is_none());
        assert_eq!(state.complete.get(), Some(true));
        assert!(
            state
                .on_reply(Reply::Default(
                    DefaultCommand::Read,
                    Err("temporary".into()),
                    None
                ))
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
                .on_reply(Reply::Default(
                    DefaultCommand::Read,
                    Err("failed".into()),
                    Some(RegistrationStatus {
                        registered: false,
                        enabled: false
                    })
                ))
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
