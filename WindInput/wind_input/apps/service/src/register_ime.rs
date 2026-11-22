//! 困困输入法的系统注册与当前用户启用。

use anyhow::{Context, Result, bail, ensure};
use serde::Serialize;
use std::ffi::OsString;
use std::fs;
use std::io::{self, Write};
use std::os::windows::{ffi::OsStringExt, process::CommandExt};
use std::path::{Path, PathBuf};
use std::process::Command;
use wind_config::variant;
use wind_coordinator::tsf_profile_name;
use windows::Win32::{
    Foundation::{BOOL, FreeLibrary},
    System::{
        LibraryLoader::{GetProcAddress, LOAD_LIBRARY_SEARCH_SYSTEM32, LoadLibraryExW},
        SystemInformation::{GetSystemDirectoryW, GetSystemWow64DirectoryW},
    },
    UI::Shell::IsUserAnAdmin,
};
use windows::core::{PCWSTR, s, w};
use winreg::{
    RegKey,
    enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_SET_VALUE, KEY_WOW64_32KEY, KEY_WOW64_64KEY},
};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const SIMPLIFIED_CHINESE: u16 = 0x0804;
const APP_CONTAINER_ACL: &str = "*S-1-15-2-1:(RX)";

pub fn run(args: &[String]) -> i32 {
    let result = match args {
        [] => Deployment::discover().and_then(|dev| dev.register()),
        [command] if command == "enable" => Deployment::discover().and_then(|dev| dev.enable()),
        [command, format] if command == "status" && format == "--json" => Deployment::discover()
            .and_then(|dev| {
                println!("{}", serde_json::to_string(&dev.status()?)?);
                Ok(())
            }),
        _ => {
            eprintln!("用法: KunKunIME.exe system register-ime [enable | status --json]");
            return 2;
        }
    };
    match result {
        Ok(()) => 0,
        Err(error) => {
            eprintln!("{error:#}");
            1
        }
    }
}

#[derive(Serialize)]
struct Status {
    registered: bool,
    enabled: bool,
}

struct Dll {
    source: PathBuf,
    installed: PathBuf,
    regsvr32: PathBuf,
    registry_view: u32,
    machine_type: u16,
    label: &'static str,
}

struct Deployment {
    dir: PathBuf,
    system: PathBuf,
    dlls: [Dll; 2],
}

impl Deployment {
    fn discover() -> Result<Self> {
        ensure!(
            cfg!(target_arch = "x86_64"),
            "注册需要 64 位版困困输入法。"
        );
        let exe = std::env::current_exe().context("找不到当前服务程序")?;
        ensure!(
            exe.file_name()
                .is_some_and(|name| name.eq_ignore_ascii_case("KunKunIME.exe")),
            "请从困困输入法的 KunKunIME.exe 管理系统注册。"
        );
        let dir = exe.parent().context("找不到困困输入法目录")?.to_path_buf();
        let system = system_directory(|buf| unsafe { GetSystemDirectoryW(Some(buf)) })?;
        let wow64 = system_directory(|buf| unsafe { GetSystemWow64DirectoryW(Some(buf)) })?;
        let dll = |folder: &Path, name: &str, registry_view, machine_type, label| Dll {
            source: dir.join(name),
            installed: folder.join("IME").join(variant::app_dir_name()).join(name),
            regsvr32: folder.join("regsvr32.exe"),
            registry_view,
            machine_type,
            label,
        };
        let dlls = [
            dll(
                &system,
                "kunkun_tsf.dll",
                KEY_WOW64_64KEY,
                0x8664,
                "64 位",
            ),
            dll(
                &wow64,
                "kunkun_tsf_x86.dll",
                KEY_WOW64_32KEY,
                0x014c,
                "32 位",
            ),
        ];
        Ok(Self { dir, system, dlls })
    }

    fn app_key(&self) -> String {
        format!(r"Software\{}", variant::app_dir_name())
    }

    fn install_dir(&self) -> io::Result<Option<String>> {
        read_machine_string(&self.app_key(), "InstallDir", KEY_WOW64_64KEY)
    }

    fn registered(&self) -> Result<bool> {
        let install_dir = self.install_dir()?;
        let system_copy_points_here = install_dir
            .as_ref()
            .is_some_and(|p| same_path(Path::new(p), &self.dir));
        if install_dir.is_some() && !system_copy_points_here {
            return Ok(false);
        }
        for dll in &self.dlls {
            let Some(server) = dll.registered_path()? else {
                return Ok(false);
            };
            let server = Path::new(&server);
            // 便携版也支持在程序目录直接注册 DLL，此时可直接从 DLL 所在目录定位服务。
            if !server.is_file()
                || !(same_path(server, &dll.source)
                    || (system_copy_points_here && same_path(server, &dll.installed)))
            {
                return Ok(false);
            }
        }
        Ok(tsf_profile_name::current_description()?.is_some())
    }

    fn status(&self) -> Result<Status> {
        let registered = self.registered()?;
        let enabled = registered && self.user_enabled()?;
        Ok(Status {
            registered,
            enabled,
        })
    }

    fn register(&self) -> Result<()> {
        ensure!(
            unsafe { IsUserAnAdmin() }.as_bool(),
            "系统注册需要管理员权限。"
        );
        if let Some(current) = self.install_dir()? {
            ensure!(
                same_path(Path::new(&current), &self.dir),
                "另一个目录已经注册了困困输入法，请从该目录管理注册（{current}）。"
            );
        }

        // 先校验两个架构的源文件与 COM 实际指向，避免在可预知的错误下只注册一半。
        let mut contents = Vec::with_capacity(self.dlls.len());
        for dll in &self.dlls {
            if let Some(current) = dll.registered_path()? {
                let current = Path::new(&current);
                ensure!(
                    same_path(current, &dll.source)
                        || (same_path(current, &dll.installed) && self.install_dir()?.is_some()),
                    "{}困困输入法组件已由其他目录注册（{}）。",
                    dll.label,
                    current.display()
                );
            }
            let bytes = fs::read(&dll.source).with_context(|| {
                format!("无法读取{} TSF 组件 {}", dll.label, dll.source.display())
            })?;
            ensure!(
                pe_machine(&bytes) == Some(dll.machine_type),
                "{} TSF 组件不是预期架构的 Windows DLL。",
                dll.label
            );
            contents.push(bytes);
        }

        if self.registered()?
            && self.dlls.iter().zip(&contents).all(|(dll, bytes)| {
                fs::read(&dll.installed).is_ok_and(|installed| installed == *bytes)
            })
        {
            return Ok(());
        }

        // 与官方部署一致，系统目录中的 DLL 用 InstallDir 找回困困输入法的运行目录。
        let (key, _) = RegKey::predef(HKEY_LOCAL_MACHINE)
            .create_subkey_with_flags(self.app_key(), KEY_SET_VALUE | KEY_WOW64_64KEY)
            .context("无法写入困困输入法的系统登记目录")?;
        key.set_value("InstallDir", &self.dir.as_os_str().to_os_string())
            .context("无法记录困困输入法安装目录")?;

        for (dll, bytes) in self.dlls.iter().zip(&contents) {
            write_dll(&dll.installed, bytes).with_context(|| {
                format!(
                    "无法部署{} TSF 组件，若文件正在使用，请重启后再试",
                    dll.label
                )
            })?;
            run_hidden(
                Command::new(self.system.join("icacls.exe"))
                    .arg(&dll.installed)
                    .args(["/grant", APP_CONTAINER_ACL]),
                "设置 TSF 组件读取权限",
            )?;
            run_hidden(
                Command::new(&dll.regsvr32).arg("/s").arg(&dll.installed),
                &format!("注册{} TSF 组件", dll.label),
            )?;
        }
        ensure!(self.registered()?, "Windows 未返回完整的 TSF 注册结果。");
        Ok(())
    }

    fn enable(&self) -> Result<()> {
        ensure!(self.registered()?, "请先完成困困输入法的系统注册。");
        if self.user_enabled()? {
            return Ok(());
        }

        // 仅从 System32 加载 input.dll。标志 0 只启用目标 TIP，沿用当前默认输入法。
        let tip: Vec<_> = tip_string(true).encode_utf16().chain([0]).collect();
        let module = unsafe { LoadLibraryExW(w!("input.dll"), None, LOAD_LIBRARY_SEARCH_SYSTEM32) }
            .context("无法加载 Windows 输入法组件")?;
        let result = unsafe {
            GetProcAddress(module, s!("InstallLayoutOrTip")).map(|function| {
                let install: unsafe extern "system" fn(PCWSTR, u32) -> BOOL =
                    std::mem::transmute(function);
                install(PCWSTR(tip.as_ptr()), 0).as_bool()
            })
        };
        let _ = unsafe { FreeLibrary(module) };
        ensure!(result == Some(true), "Windows 未能启用困困输入法。");
        ensure!(
            self.user_enabled()?,
            "Windows 输入法列表尚未更新，请打开「语言选项」并添加困困输入法。"
        );
        Ok(())
    }

    fn user_enabled(&self) -> Result<bool> {
        let tip = tip_string(false);
        // Get-WinUserLanguageList 与 dev.ps1 的启用检查一致，读取调用者当前的 Windows 账户。
        // 退出码 3 表示未添加，与 PowerShell 自身报错的退出码 1 分开。
        let script = format!(
            "$ErrorActionPreference = 'Stop'; \
             foreach ($language in Get-WinUserLanguageList) {{ \
             if ($language.InputMethodTips -contains '{tip}') {{ exit 0 }} \
             }}; exit 3"
        );
        let output = Command::new(self.system.join(r"WindowsPowerShell\v1.0\powershell.exe"))
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .context("无法查询当前用户的输入法列表")?;
        match output.status.code() {
            Some(0) => Ok(true),
            Some(3) => Ok(false),
            _ => bail!(
                "Windows 无法查询当前用户的输入法列表（{}）。",
                output.status
            ),
        }
    }
}

impl Dll {
    fn registered_path(&self) -> io::Result<Option<String>> {
        let (clsid, _) = tsf_profile_name::registration_guids();
        read_machine_string(
            &format!(r"Software\Classes\CLSID\{clsid}\InprocServer32"),
            "",
            self.registry_view,
        )
    }
}

fn read_machine_string(key: &str, value_name: &str, view: u32) -> io::Result<Option<String>> {
    match RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(key, KEY_READ | view)
        .and_then(|key| key.get_value(value_name))
    {
        Ok(value) => Ok(Some(value)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error),
    }
}

fn system_directory(get: impl FnOnce(&mut [u16]) -> u32) -> Result<PathBuf> {
    let mut buffer = [0u16; 260];
    let len = get(&mut buffer) as usize;
    ensure!(
        len != 0 && len < buffer.len(),
        "无法确定 Windows 系统目录。"
    );
    Ok(PathBuf::from(OsString::from_wide(&buffer[..len])))
}

fn run_hidden(command: &mut Command, description: &str) -> Result<()> {
    let status = command
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .with_context(|| format!("无法{description}"))?
        .status;
    ensure!(status.success(), "{description}失败（{status}）。");
    Ok(())
}

fn tip_string(win32: bool) -> String {
    let (clsid, profile) = tsf_profile_name::registration_guids();
    let prefix = if win32 { "0x" } else { "" };
    format!("{prefix}{SIMPLIFIED_CHINESE:04X}:{clsid}{profile}")
}

fn same_path(first: &Path, second: &Path) -> bool {
    let first = fs::canonicalize(first).unwrap_or_else(|_| first.to_path_buf());
    let second = fs::canonicalize(second).unwrap_or_else(|_| second.to_path_buf());
    first.as_os_str().eq_ignore_ascii_case(second.as_os_str())
}

fn pe_machine(bytes: &[u8]) -> Option<u16> {
    if bytes.get(..2)? != b"MZ" {
        return None;
    }
    let header = u32::from_le_bytes(bytes.get(0x3c..0x40)?.try_into().ok()?) as usize;
    let coff = bytes.get(header..)?.get(..24)?;
    if &coff[..4] != b"PE\0\0" {
        return None;
    }
    let characteristics = u16::from_le_bytes(coff[22..24].try_into().ok()?);
    const IMAGE_FILE_DLL: u16 = 0x2000;
    (characteristics & IMAGE_FILE_DLL != 0).then(|| u16::from_le_bytes([coff[4], coff[5]]))
}

fn write_dll(path: &Path, bytes: &[u8]) -> Result<()> {
    if fs::read(path).is_ok_and(|installed| installed == bytes) {
        return Ok(());
    }
    fs::create_dir_all(path.parent().context("TSF 组件目标没有父目录")?)?;
    let temp = path.with_extension(format!("{}.tmp", std::process::id()));
    let mut pending = fs::File::create_new(&temp)?;
    let result = (|| -> io::Result<()> {
        pending.write_all(bytes)?;
        pending.sync_all()?;
        drop(pending);
        fs::rename(&temp, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result.context("写入系统 TSF 文件失败")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_or_executable_pe_is_not_a_tsf_dll() {
        let mut bytes = [0; 88];
        bytes[..2].copy_from_slice(b"MZ");
        bytes[0x3c..0x40].copy_from_slice(&64u32.to_le_bytes());
        bytes[64..68].copy_from_slice(b"PE\0\0");
        bytes[68..70].copy_from_slice(&0x8664u16.to_le_bytes());
        assert_eq!(pe_machine(&bytes), None);
        bytes[86..88].copy_from_slice(&0x2000u16.to_le_bytes());
        assert_eq!(pe_machine(&bytes), Some(0x8664));
        assert_eq!(pe_machine(&bytes[..80]), None);
        bytes[0x3c..0x40].copy_from_slice(&u32::MAX.to_le_bytes());
        assert_eq!(pe_machine(&bytes), None);
    }

    #[test]
    fn update_replaces_file_and_retry_leaves_no_backup() {
        let folder = tempfile::tempdir().unwrap();
        let path = folder.path().join("kunkun_tsf.dll");
        write_dll(&path, b"first").unwrap();
        write_dll(&path, b"second").unwrap();
        let modified = fs::metadata(&path).unwrap().modified().unwrap();
        write_dll(&path, b"second").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"second");
        assert_eq!(fs::metadata(&path).unwrap().modified().unwrap(), modified);
        assert_eq!(fs::read_dir(folder.path()).unwrap().count(), 1);
    }
}
