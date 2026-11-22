use std::path::PathBuf;

fn usage() -> ! {
    eprintln!(
        "用法: KunKunIME --extension-host --extensions-dir <目录> [--log-dir <目录>] [--core-version <版本>]"
    );
    std::process::exit(2);
}

pub fn run_from_args(mut args: impl Iterator<Item = std::ffi::OsString>) -> i32 {
    let mut extensions_dir = None;
    let mut log_dir = None;
    let mut core_version = String::new();
    while let Some(a) = args.next() {
        let mut val = || args.next().unwrap_or_else(|| usage());
        match a.to_str() {
            Some("--extensions-dir") => extensions_dir = Some(PathBuf::from(val())),
            Some("--log-dir") => log_dir = Some(PathBuf::from(val())),
            Some("--core-version") => core_version = val().to_string_lossy().into_owned(),
            _ => usage(),
        }
    }
    let Some(extensions_dir) = extensions_dir else {
        usage()
    };
    crate::run(crate::Opts {
        extensions_dir,
        log_dir,
        core_version,
    })
}
