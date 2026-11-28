//! Current-user Shell associations are owned by their complete open command. The TSF
//! hotkeys, in contrast, are owned by the registered TIP and have no executable path.
use super::{Deployment, RegKey, Result, io};
use kunkun_settings::windows_import_associations::{
    ALL, command_for_exe, icon_value_for_exe, notify_shell_assoc_changed,
};
use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_SET_VALUE};

const CLASSES: &str = r"Software\Classes";
const HOTKEYS: &str = r"Software\Microsoft\CTF\DirectSwitchHotkeys";

impl Deployment {
    pub(super) fn unregister_associations(&self) -> Result<()> {
        let exe = self.dir.join("KunKunIME.exe");
        if remove_shell_associations(&RegKey::predef(HKEY_CURRENT_USER), &exe.to_string_lossy())? {
            notify_shell_assoc_changed();
        }
        Ok(())
    }
}

fn optional<T>(result: io::Result<T>) -> io::Result<Option<T>> {
    match result {
        Ok(value) => Ok(Some(value)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

fn text_value(key: &RegKey, name: &str) -> io::Result<Option<String>> {
    match key.get_value(name) {
        Err(e) if e.kind() == io::ErrorKind::InvalidData => Ok(None), // custom non-string value
        result => optional(result),
    }
}

fn remove_matching(user: &RegKey, path: &str, name: &str, value: &str) -> io::Result<bool> {
    let Some(key) = optional(user.open_subkey_with_flags(path, KEY_READ | KEY_SET_VALUE))? else {
        return Ok(false);
    };
    if !text_value(&key, name)?.is_some_and(|s| s.eq_ignore_ascii_case(value)) {
        return Ok(false);
    }
    optional(key.delete_value(name))?;
    Ok(true)
}

fn prune_empty(user: &RegKey, path: &str) -> io::Result<()> {
    if let Some(key) = optional(user.open_subkey(path))?
        && key.enum_values().next().is_none()
        && key.enum_keys().next().is_none()
    {
        drop(key);
        optional(user.delete_subkey(path))?;
    }
    Ok(())
}

fn remove_shell_associations(user: &RegKey, exe: &str) -> io::Result<bool> {
    let command = command_for_exe(exe);
    let mut changed = false;
    for (scheme, assoc) in
        std::iter::once(("kunkunime", None)).chain(ALL.iter().map(|a| (a.progid, Some(a))))
    {
        let root = format!(r"{CLASSES}\{scheme}");
        let shell = format!(r"{root}\shell");
        let open = format!(r"{shell}\open");
        let handler = format!(r"{open}\command");
        let Some(key) = optional(user.open_subkey(&handler))? else {
            continue;
        };
        if !text_value(&key, "")?.is_some_and(|s| s.eq_ignore_ascii_case(&command)) {
            continue;
        }
        drop(key);

        if let Some(a) = assoc {
            let ext = format!(r"{CLASSES}\{}", a.ext);
            if remove_matching(user, &ext, "", a.progid)? {
                prune_empty(user, &ext)?;
            }
            let icon = format!(r"{root}\DefaultIcon");
            if remove_matching(user, &icon, "", &icon_value_for_exe(exe, a.icon_id))? {
                prune_empty(user, &icon)?;
            }
            remove_matching(user, &root, "", a.type_name)?;
        } else {
            remove_matching(user, &root, "URL Protocol", "")?;
            remove_matching(user, &root, "", "URL:困困输入法协议")?;
        }
        // Keep the ownership marker until the other values have been removed so failures
        // can be retried. Never recursively delete the user's custom verbs, values or icons.
        changed |= remove_matching(user, &handler, "", &command)?;
        for path in [&handler, &open, &shell, &root] {
            prune_empty(user, path)?;
        }
    }
    Ok(changed)
}

pub(super) fn remove_direct_switch_hotkeys(user: &RegKey) -> io::Result<()> {
    let Some(parent) = optional(user.open_subkey(HOTKEYS))? else {
        return Ok(());
    };
    let (clsid, profile, preserved) = wind_coordinator::direct_switch::tip_guid_strings();
    let slots = parent.enum_keys().collect::<io::Result<Vec<_>>>()?;
    for slot in slots {
        if u32::from_str_radix(&slot, 16).is_err() {
            continue;
        }
        let path = format!(r"{HOTKEYS}\{slot}");
        let Some(key) = optional(user.open_subkey(&path))? else {
            continue;
        };
        let cls = text_value(&key, "CLSID")?;
        let prof = text_value(&key, "Profile")?;
        let own_id =
            text_value(&key, "PreservedKeyId")?.is_some_and(|s| s.eq_ignore_ascii_case(preserved));
        if cls.as_ref().is_some_and(|s| !s.eq_ignore_ascii_case(clsid))
            || prof
                .as_ref()
                .is_some_and(|s| !s.eq_ignore_ascii_case(profile))
            || (cls.is_none() && prof.is_none() && !own_id)
        {
            continue;
        }
        drop(key);
        let Some(key) = optional(user.open_subkey_with_flags(&path, KEY_SET_VALUE))? else {
            continue;
        };
        for name in [
            "Modifiers",
            "VirtualKey",
            "LangId",
            "PreservedKeyId",
            "Profile",
            "CLSID",
        ] {
            optional(key.delete_value(name))?;
        }
        drop(key);
        prune_empty(user, &path)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use winreg::enums::KEY_WRITE;

    #[test]
    fn uninstall_links_and_hotkeys_keep_other_defaults_handlers_and_custom_values() {
        let temp = tempfile::tempdir().unwrap();
        let user = RegKey::load_app_key(temp.path().join("shell.hiv"), true).unwrap();
        let ours = r"C:\My Programs\KunKunIME.exe";
        let other = r"D:\Other\KunKunIME.exe";
        let write = |path: &str, name: &str, val: &str| {
            user.create_subkey_with_flags(path, KEY_WRITE)
                .unwrap()
                .0
                .set_value(name, &val)
                .unwrap();
        };
        let read = |path: &str, name: &str| {
            user.open_subkey(path)
                .ok()
                .and_then(|k| k.get_value::<String, _>(name).ok())
        };
        let scheme = format!(r"{CLASSES}\kunkunime");
        let package = format!(r"{CLASSES}\KunKunIME.Package");
        let theme = format!(r"{CLASSES}\KunKunIME.Theme");
        write(
            &format!(r"{scheme}\shell\open\command"),
            "",
            &command_for_exe(ours),
        );
        write(&scheme, "URL Protocol", "");
        write(&format!(r"{scheme}\shell\custom"), "", "saved");
        write(
            &format!(r"{package}\shell\open\command"),
            "",
            &command_for_exe(ours),
        );
        write(&format!(r"{package}\DefaultIcon"), "", "custom icon");
        write(&format!(r"{CLASSES}\.wpkg"), "", "Other.Default");
        write(
            &format!(r"{theme}\shell\open\command"),
            "",
            &command_for_exe(other),
        );
        write(&format!(r"{CLASSES}\.wtheme"), "", "KunKunIME.Theme");
        assert!(remove_shell_associations(&user, ours).unwrap());
        assert!(!remove_shell_associations(&user, ours).unwrap());
        assert!(read(&scheme, "URL Protocol").is_none());
        assert_eq!(
            read(&format!(r"{scheme}\shell\custom"), "").as_deref(),
            Some("saved")
        );
        assert_eq!(
            read(&format!(r"{CLASSES}\.wpkg"), "").as_deref(),
            Some("Other.Default")
        );
        assert_eq!(
            read(&format!(r"{CLASSES}\.wtheme"), "").as_deref(),
            Some("KunKunIME.Theme")
        );
        assert_eq!(
            read(&format!(r"{package}\DefaultIcon"), "").as_deref(),
            Some("custom icon")
        );
        assert_eq!(
            read(&format!(r"{theme}\shell\open\command"), ""),
            Some(command_for_exe(other))
        );
        write(
            &format!(r"{theme}\shell\open\command"),
            "",
            &command_for_exe(ours),
        );
        write(&theme, "", "困困输入法主题包");
        write(
            &format!(r"{CLASSES}\.wtheme\OpenWithProgids"),
            "Other.Viewer",
            "",
        );
        assert!(remove_shell_associations(&user, ours).unwrap());
        assert!(user.open_subkey(&theme).is_err());
        assert!(read(&format!(r"{CLASSES}\.wtheme"), "").is_none());
        assert_eq!(
            read(
                &format!(r"{CLASSES}\.wtheme\OpenWithProgids"),
                "Other.Viewer"
            )
            .as_deref(),
            Some("")
        );
        let (clsid, profile, preserved) = wind_coordinator::direct_switch::tip_guid_strings();
        let ours = format!(r"{HOTKEYS}\00001000");
        let foreign = format!(r"{HOTKEYS}\00001001");
        write(&ours, "CLSID", clsid);
        write(&ours, "Profile", profile);
        write(&ours, "VirtualKey", "00000041");
        write(&foreign, "CLSID", "{BEEF}");
        write(&foreign, "PreservedKeyId", preserved);
        remove_direct_switch_hotkeys(&user).unwrap();
        remove_direct_switch_hotkeys(&user).unwrap();
        assert!(user.open_subkey(&ours).is_err());
        assert_eq!(read(&foreign, "PreservedKeyId").as_deref(), Some(preserved));
    }
}
