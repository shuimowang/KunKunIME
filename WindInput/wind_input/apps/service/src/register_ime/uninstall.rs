use super::*;
use std::os::windows::fs::MetadataExt;
use winreg::enums::{HKEY_CURRENT_USER, KEY_WRITE};

const UNINSTALL_PARENT: &str = r"Software\Microsoft\Windows\CurrentVersion\Uninstall";
const UNINSTALL_NAME: &str = "KunKunIME";
const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct UninstallStatus {
    machine_needed: bool,
    foreign_registration: bool,
}

pub(crate) fn ensure_uninstall_entry() -> Result<()> {
    let deployment = Deployment::discover()?;
    if deployment.registered()? {
        deployment.update_uninstall_entry()?;
    }
    Ok(())
}

fn missing_ok(result: io::Result<()>) -> io::Result<()> {
    match result {
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        other => other,
    }
}

impl Deployment {
    fn ownership(&self, install: Option<&Path>, servers: &[Option<String>; 2]) -> UninstallStatus {
        let owned_system = install.is_some_and(|p| same_path(p, &self.dir));
        let foreign_registration = (install.is_some() && !owned_system)
            || self.dlls.iter().zip(servers).any(|(dll, server)| {
                server.as_deref().is_some_and(|p| {
                    !same_path(Path::new(p), &dll.source)
                        && !(owned_system && same_path(Path::new(p), &dll.installed))
                })
            });
        UninstallStatus {
            machine_needed: !foreign_registration
                && (owned_system || servers.iter().any(Option::is_some)),
            foreign_registration,
        }
    }

    pub(super) fn uninstall_status(&self) -> Result<UninstallStatus> {
        let install = self.install_dir()?;
        let servers = [
            self.dlls[0].registered_path()?,
            self.dlls[1].registered_path()?,
        ];
        Ok(self.ownership(install.as_deref().map(Path::new), &servers))
    }

    fn require_ownership(&self) -> Result<UninstallStatus> {
        let status = self.uninstall_status()?;
        ensure!(
            !status.foreign_registration,
            "Windows 中的困困输入法属于另一个安装位置，已保留该安装的注册和输入法设置。"
        );
        Ok(status)
    }

    pub(super) fn unregister_user(&self) -> Result<()> {
        self.require_ownership()?;
        self.default_input_method("release-own-default")?;
        if self.user_enabled()? {
            self.change_user_tip(1)?; // ILOT_UNINSTALL
            ensure!(
                !self.user_enabled()?,
                "Windows 输入法列表仍包含困困输入法，请重试。"
            );
        }
        super::associations::remove_direct_switch_hotkeys(&RegKey::predef(HKEY_CURRENT_USER))?;
        Ok(())
    }

    pub(super) fn unregister_machine(&self) -> Result<()> {
        if !self.require_ownership()?.machine_needed {
            return Ok(());
        }
        ensure!(
            unsafe { IsUserAnAdmin() }.as_bool(),
            "注销系统输入法组件需要管理员权限。"
        );
        let owns_system_files = self.install_dir()?.is_some();
        let (clsid, _) = tsf_profile_name::registration_guids();
        for dll in &self.dlls {
            // Only the fixed system copy is ever executed elevated. Legacy direct registrations
            // and missing files are cleaned by their exact, ownership-checked registry identity.
            if owns_system_files && system_copy_present(dll)? {
                run_hidden(
                    Command::new(&dll.regsvr32)
                        .args(["/u", "/s"])
                        .arg(&dll.installed),
                    &format!("注销{} TSF 组件", dll.label),
                )?;
            }
            remove_exact_registration(
                &RegKey::predef(HKEY_LOCAL_MACHINE),
                clsid,
                dll.registry_view,
            )?;
            ensure!(
                dll.registered_path()?.is_none(),
                "{} TSF 组件仍已注册。",
                dll.label
            );
        }

        // Leave InstallDir in place until every removal succeeds. A locked DLL can then be
        // retried from this same install, even after both COM registrations have gone.
        if owns_system_files {
            for dll in &self.dlls {
                if system_copy_present(dll)? {
                    fs::remove_file(&dll.installed).with_context(|| {
                        format!(
                            "无法删除{}组件 {}。请保存工作，关闭使用输入法的应用后再次运行卸载。",
                            dll.label,
                            dll.installed.display()
                        )
                    })?;
                }
                let directory = dll.installed.parent().context("系统组件目录无效")?;
                match fs::read_dir(directory) {
                    Ok(mut entries) => {
                        if entries.next().transpose()?.is_none() {
                            missing_ok(fs::remove_dir(directory))?;
                        }
                    }
                    Err(e) if e.kind() == io::ErrorKind::NotFound => {}
                    Err(e) => return Err(e.into()),
                }
            }
            let software = RegKey::predef(HKEY_LOCAL_MACHINE)
                .open_subkey_with_flags("Software", KEY_READ | KEY_WRITE | KEY_WOW64_64KEY)?;
            let app =
                software.open_subkey_with_flags(variant::app_dir_name(), KEY_READ | KEY_WRITE)?;
            let current: String = app.get_value("InstallDir")?;
            ensure!(
                same_path(Path::new(&current), &self.dir),
                "卸载期间 Windows 的安装位置已改变。"
            );
            missing_ok(app.delete_value("Dota2CompatAlias"))?;
            missing_ok(app.delete_value("InstallDir"))?;
            if app.enum_keys().next().is_none() && app.enum_values().next().is_none() {
                drop(app);
                missing_ok(software.delete_subkey(variant::app_dir_name()))?;
            }
        }
        ensure!(
            !self.uninstall_status()?.machine_needed,
            "Windows 仍保留这个安装的注册，请重试。"
        );
        Ok(())
    }

    pub(super) fn update_uninstall_entry(&self) -> Result<()> {
        self.update_uninstall_entry_at(&RegKey::predef(HKEY_CURRENT_USER))
    }

    fn update_uninstall_entry_at(&self, user: &RegKey) -> Result<()> {
        let (parent, _) = user.create_subkey_with_flags(UNINSTALL_PARENT, KEY_READ | KEY_WRITE)?;
        let (entry, disposition) = parent.create_subkey(UNINSTALL_NAME)?;
        if disposition == winreg::enums::REG_CREATED_NEW_KEY
            || (entry.enum_values().next().is_none() && entry.enum_keys().next().is_none())
        {
            entry.set_value("InstallLocation", &self.dir.as_os_str())?;
        } else {
            let owned: String = entry
                .get_value("InstallLocation")
                .context("Windows 卸载项缺少安装目录，已保留原有记录")?;
            ensure!(
                same_path(Path::new(&owned), &self.dir),
                "Windows 已安装的应用中有另一个困困安装的记录。"
            );
        }
        let exe = self.dir.join("KunKunIME.exe");
        let uninstall = format!(r#""{}" system uninstall"#, exe.display());
        let icon = format!(r#""{}",0"#, exe.display());
        for (name, value) in [
            ("DisplayName", "困困输入法"),
            ("DisplayVersion", env!("WIND_APP_VERSION")),
            ("UninstallString", uninstall.as_str()),
            ("DisplayIcon", icon.as_str()),
        ] {
            if entry.get_value::<String, _>(name).ok().as_deref() != Some(value) {
                entry.set_value(name, &value)?;
            }
        }
        for name in ["NoModify", "NoRepair"] {
            if entry.get_value::<u32, _>(name).ok() != Some(1) {
                entry.set_value(name, &1u32)?;
            }
        }
        Ok(())
    }
}

fn system_copy_present(dll: &Dll) -> Result<bool> {
    let dir = dll.installed.parent().context("系统组件目录无效")?;
    for path in [
        dir.parent().context("系统 IME 目录无效")?,
        dir,
        &dll.installed,
    ] {
        match fs::symlink_metadata(path) {
            Ok(m) => ensure!(
                m.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT == 0,
                "系统组件路径包含重解析点：{}",
                path.display()
            ),
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(false),
            Err(e) => return Err(e.into()),
        }
    }
    Ok(true)
}

fn remove_exact_registration(machine: &RegKey, clsid: &str, view: u32) -> Result<()> {
    for parent in [r"Software\Classes\CLSID", r"Software\Microsoft\CTF\TIP"] {
        let key = match machine.open_subkey_with_flags(parent, KEY_READ | KEY_WRITE | view) {
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            other => other?,
        };
        let owned = match key.open_subkey_with_flags(clsid, winreg::enums::KEY_ALL_ACCESS | view) {
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            other => other?,
        };
        owned.delete_subkey_all("")?;
        drop(owned);
        missing_ok(key.delete_subkey_with_flags(clsid, view))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> Deployment {
        Deployment {
            dir: PathBuf::from(r"C:\Apps\KunKun"),
            system: PathBuf::from(r"C:\Windows\System32"),
            dlls: ["64", "32"].map(|arch| Dll {
                label: arch,
                machine_type: 0,
                registry_view: 0,
                regsvr32: PathBuf::new(),
                source: PathBuf::from(format!(r"C:\Apps\KunKun\test{arch}.dll")),
                installed: PathBuf::from(format!(
                    r"C:\Windows\System{arch}\IME\KunKunIME\test{arch}.dll"
                )),
            }),
        }
    }

    #[test]
    fn uninstall_status_respects_other_installations_and_partial_registration() {
        let dev = fixture();
        let own = Some(dev.dir.as_path());
        let foreign = Some(Path::new(r"C:\Another\KunKun"));
        let sys = dev.dlls[0].installed.to_string_lossy().into_owned();
        let direct = dev.dlls[0].source.to_string_lossy().into_owned();
        let other = r"C:\Another\KunKun\test32.dll".to_owned();
        for (location, servers, needed, protected) in [
            (None, [None, None], false, false),
            (own, [Some(sys.clone()), None], true, false),
            (own, [None, None], true, false), // previous try removed COM, DLL still locked
            (None, [Some(direct.clone()), None], true, false),
            (None, [Some(sys), None], false, true), // unknown owner
            (foreign, [None, None], false, true),
            (own, [Some(direct), Some(other)], false, true),
        ] {
            let state = dev.ownership(location, &servers);
            assert_eq!(
                (state.machine_needed, state.foreign_registration),
                (needed, protected)
            );
        }
    }

    #[test]
    fn uninstall_entry_and_tsf_cleanup_use_only_the_owned_registry_entries() {
        // This hive is a private temporary file accessed only through its handle.
        let dir = tempfile::tempdir().unwrap();
        let hive = RegKey::load_app_key(dir.path().join("uninstall.hiv"), true).unwrap();
        let dev = fixture();
        dev.update_uninstall_entry_at(&hive).unwrap();
        let entry_path = format!(r"{UNINSTALL_PARENT}\{UNINSTALL_NAME}");
        let entry = hive
            .open_subkey_with_flags(&entry_path, KEY_READ | KEY_WRITE)
            .unwrap();
        assert_eq!(
            entry.get_value::<String, _>("UninstallString").unwrap(),
            r#""C:\Apps\KunKun\KunKunIME.exe" system uninstall"#
        );
        entry.set_value("DisplayVersion", &"older").unwrap();
        dev.update_uninstall_entry_at(&hive).unwrap();
        assert_eq!(
            entry.get_value::<String, _>("DisplayVersion").unwrap(),
            env!("WIND_APP_VERSION")
        );
        entry.set_value("InstallLocation", &r"C:\Other").unwrap();
        assert!(dev.update_uninstall_entry_at(&hive).is_err());
        assert_eq!(
            entry.get_value::<String, _>("InstallLocation").unwrap(),
            r"C:\Other"
        );

        let (clsid, _) = tsf_profile_name::registration_guids();
        for base in [r"Software\Classes\CLSID", r"Software\Microsoft\CTF\TIP"] {
            hive.create_subkey(format!(r"{base}\{clsid}\Sub")).unwrap();
            hive.create_subkey(format!(r"{base}\Unrelated")).unwrap();
        }
        remove_exact_registration(&hive, clsid, 0).unwrap();
        remove_exact_registration(&hive, clsid, 0).unwrap();
        for base in [r"Software\Classes\CLSID", r"Software\Microsoft\CTF\TIP"] {
            assert!(hive.open_subkey(format!(r"{base}\{clsid}")).is_err());
            assert!(hive.open_subkey(format!(r"{base}\Unrelated")).is_ok());
        }
    }
}
