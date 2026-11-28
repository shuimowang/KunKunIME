//! Build the uninstaller's trusted inventory from the same inputs used to package Windows.
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::path::Path;
use std::process::Command;

type Files = BTreeMap<String, Vec<String>>;

fn add_file(files: &mut Files, name: &str, source: &Path) {
    let bytes = fs::read(source).unwrap_or_else(|e| panic!("{}: {e}", source.display()));
    let mut hashes = vec![format!("{:x}", Sha256::digest(&bytes))];
    // Git checkouts can materialize the same source text as LF locally and CRLF on
    // the release runner. Each allowed byte representation has an exact digest.
    if let Ok(text) = std::str::from_utf8(&bytes)
        && !text.contains('\0')
        && text.contains('\n')
    {
        let lf = text.replace("\r\n", "\n");
        let crlf = lf.replace('\n', "\r\n");
        for alternate in [&lf, &crlf] {
            if alternate.as_bytes() != bytes {
                hashes.push(format!("{:x}", Sha256::digest(alternate.as_bytes())));
            }
        }
    }
    files.insert(name.to_owned(), hashes);
}

// Matches the source dictionary filters in scripts/build-windows.ps1.
fn excluded_data(relative: &str) -> bool {
    relative == "comments-src"
        || relative == "schemas/wubi86"
        || relative.strip_prefix("schemas/").is_some_and(|name| {
            name.starts_with("wubi") && !name.contains('/') && name.ends_with(".schema.toml")
        })
        || matches!(
            relative,
            "schemas/aux_code/shouyou.txt" | "schemas/aux_code/shouyou_plus.txt"
        )
}

fn walk(files: &mut Files, root: &Path, dir: &Path, prefix: &str, filter_data: bool) {
    for item in fs::read_dir(dir).unwrap_or_else(|e| panic!("{}: {e}", dir.display())) {
        let item = item.unwrap();
        let path = item.path();
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if fs::symlink_metadata(&path).unwrap().file_attributes() & 0x400 != 0 {
                continue;
            }
        }
        let relative = path
            .strip_prefix(root)
            .unwrap()
            .to_str()
            .unwrap()
            .replace('\\', "/");
        if filter_data && excluded_data(&relative) {
            continue;
        }
        let kind = item.file_type().unwrap();
        if kind.is_dir() {
            walk(files, root, &path, prefix, filter_data);
        } else if kind.is_file() {
            add_file(files, &format!("{prefix}/{relative}"), &path);
        }
    }
}

pub fn generate(engine: &Path) {
    let project = engine.parent().unwrap();
    let mut files = Files::new();
    for (name, path) in [
        ("使用说明.md", "docs/使用说明.md"),
        ("MCP.md", "docs/MCP.md"),
        (
            "卸载Windows输入法注册.md",
            "scripts/卸载Windows输入法注册.md",
        ),
        ("licenses/GPL-3.0.txt", "WindInput/LICENSE"),
        ("licenses/LGPL-3.0.txt", "WindInput/data/emoji/LICENSE"),
        ("licenses/wind-setting-LICENSE.txt", "wind-setting/LICENSE"),
        ("licenses/Apache-2.0.txt", "wind-ui-rust/LICENSE-APACHE"),
        ("licenses/windui-MIT.txt", "wind-ui-rust/LICENSE-MIT"),
        ("licenses/NOTICE-upstream.md", "WindInput/NOTICE.md"),
        ("licenses/ORIGINS.md", "ORIGINS.md"),
        ("licenses/THIRD-PARTY.md", "licenses/THIRD-PARTY.md"),
    ] {
        let source = project.join(path);
        println!("cargo:rerun-if-changed={}", source.display());
        add_file(&mut files, name, &source);
    }
    for (dir, path, filter) in [
        ("data", engine.join("data"), true),
        ("data_custom", engine.join("data_custom"), true),
        ("licenses/data", project.join("licenses/data"), false),
    ] {
        println!("cargo:rerun-if-changed={}", path.display());
        walk(&mut files, &path, &path, dir, filter);
    }
    for name in [
        "KunKunIME.exe",
        "kunkun_tsf.dll",
        "kunkun_tsf_x86.dll",
        "licenses/rust/index.json",
        "licenses/rust/README.md",
    ] {
        files.insert(name.to_owned(), Vec::new());
    }
    let compiler = std::env::var_os("RUSTC").unwrap();
    let sysroot = Command::new(compiler)
        .args(["--print", "sysroot"])
        .output()
        .expect("rustc --print sysroot");
    assert!(sysroot.status.success());
    let stdout = String::from_utf8(sysroot.stdout).unwrap();
    let docs = Path::new(stdout.trim()).join("share/doc/rust");
    // Minimal Rust installations can build the service on their own. A release build
    // also requires these notices in collect-cargo-notices.py before it can ship.
    let std_notice = docs.join("COPYRIGHT-library.html");
    let std_licenses = docs.join("licenses");
    if std_notice.is_file() && std_licenses.is_dir() {
        println!("cargo:rerun-if-changed={}", std_notice.display());
        println!("cargo:rerun-if-changed={}", std_licenses.display());
        add_file(
            &mut files,
            "licenses/rust/rust-stdlib/COPYRIGHT-library.html",
            &std_notice,
        );
        walk(
            &mut files,
            &std_licenses,
            &std_licenses,
            "licenses/rust/rust-stdlib/licenses",
            false,
        );
    }
    let output = std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let mut names = String::new();
    let mut hashes = String::new();
    let mut alternates = String::new();
    for (name, digests) in files {
        names.push_str(&name);
        names.push('\n');
        if let Some((hash, others)) = digests.split_first() {
            hashes.push_str(&format!("{hash}\t{name}\n"));
            for other in others {
                alternates.push_str(&format!("{other}\t{name}\n"));
            }
        }
    }
    fs::write(output.join("uninstall-owned-paths.txt"), names).unwrap();
    fs::write(output.join("uninstall-owned-sha256.txt"), hashes).unwrap();
    fs::write(
        output.join("uninstall-owned-alternate-sha256.txt"),
        alternates,
    )
    .unwrap();
}
