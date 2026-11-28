//! 困困桌面版与 Android 共用 GitHub 仓库，按 Windows 正式标签和对应的 x64 ZIP 选版。
use std::io::Read;
use std::time::Duration;

use semver::Version;
use serde::Deserialize;

use super::{CheckResult, UpdateError, WINDOWS_RELEASES_PAGE, notes};

const RELEASES_API: &str = "https://api.github.com/repos/shuimowang/KunKunIME/releases";
const PER_PAGE: usize = 100;
const MAX_PAGES: usize = 5;
const MAX_PAGE_BYTES: u64 = 8 * 1024 * 1024;

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    prerelease: bool,
    body: Option<String>,
    assets: Vec<Asset>,
}

#[derive(Deserialize)]
struct Asset {
    name: String,
    browser_download_url: String,
    state: String,
    size: u64,
    digest: Option<String>,
}

pub(super) fn check(local: &str, show_current_notes: bool) -> Result<CheckResult, UpdateError> {
    let agent = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(15))
        .redirects(0)
        .try_proxy_from_env(true)
        .user_agent("KunKunIME-Windows-Update")
        .build();
    check_pages(local, show_current_notes, |page| {
        let response = agent
            .get(&format!("{RELEASES_API}?per_page={PER_PAGE}&page={page}"))
            .set("Accept", "application/vnd.github+json")
            .call()
            .map_err(|err| match err {
                ureq::Error::Status(403 | 429, _) => {
                    UpdateError::Network("GitHub 暂时限制了请求，请稍后重试。".into())
                }
                ureq::Error::Status(status, _) => {
                    UpdateError::Network(format!("GitHub 返回 HTTP {status}，请稍后重试。"))
                }
                ureq::Error::Transport(_) => {
                    UpdateError::Network("无法连接 GitHub，请检查网络或代理后重试。".into())
                }
            })?;
        if response.status() != 200 {
            return Err(UpdateError::Network(format!(
                "GitHub 返回 HTTP {}。",
                response.status()
            )));
        }
        let mut bytes = Vec::new();
        response
            .into_reader()
            .take(MAX_PAGE_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| UpdateError::Network("读取 GitHub 响应失败，请重试。".into()))?;
        if bytes.len() as u64 > MAX_PAGE_BYTES {
            return Err(UpdateError::InvalidMetadata(
                "发行列表超出大小限制。".into(),
            ));
        }
        serde_json::from_slice(&bytes)
            .map_err(|_| UpdateError::InvalidMetadata("无法解析 GitHub 发行列表。".into()))
    })
}

fn check_pages(
    local: &str,
    show_current_notes: bool,
    mut page_at: impl FnMut(usize) -> Result<Vec<Release>, UpdateError>,
) -> Result<CheckResult, UpdateError> {
    let mut newest: Option<(Version, Release)> = None;
    for page in 1..=MAX_PAGES {
        let releases = page_at(page)?;
        let complete = releases.len() < PER_PAGE;
        for item in releases {
            if item.draft || item.prerelease {
                continue;
            }
            let Some(version) = item
                .tag_name
                .strip_prefix('v')
                .and_then(|v| Version::parse(v).ok())
            else {
                continue;
            };
            if version.pre.is_empty()
                && version.build.is_empty()
                && newest
                    .as_ref()
                    .is_none_or(|(previous, _)| version > *previous)
            {
                newest = Some((version, item));
            }
        }
        if complete {
            let (version, release) = newest.ok_or_else(|| {
                UpdateError::InvalidMetadata("尚未找到 Windows 正式发行版。".into())
            })?;
            return release.result(version, local, show_current_notes);
        }
    }
    Err(UpdateError::InvalidMetadata(
        "未能读取完整发行列表，请打开发布页查看。".into(),
    ))
}

impl Release {
    fn result(
        self,
        version: Version,
        local: &str,
        show_current_notes: bool,
    ) -> Result<CheckResult, UpdateError> {
        let name = format!("KunKunIME-{version}-Windows-x64.zip");
        let expected = format!("{WINDOWS_RELEASES_PAGE}/download/{}/{name}", self.tag_name);
        let mut matches = self.assets.into_iter().filter(|asset| asset.name == name);
        let asset = matches.next().ok_or_else(|| {
            UpdateError::InvalidMetadata(format!(
                "v{version} 的 Windows 程序包尚未就绪，请稍后重试。"
            ))
        })?;
        if matches.next().is_some()
            || asset.browser_download_url != expected
            || asset.state != "uploaded"
            || asset.size == 0
        {
            return Err(UpdateError::InvalidMetadata(
                "Windows 程序包的下载信息不完整。".into(),
            ));
        }
        let digest = asset
            .digest
            .as_deref()
            .and_then(|d| d.strip_prefix("sha256:"))
            .filter(|d| d.len() == 64 && d.bytes().all(|b| b.is_ascii_hexdigit()))
            .ok_or_else(|| {
                UpdateError::InvalidMetadata("Windows 程序包缺少有效的 SHA-256。".into())
            })?;
        let mut result = CheckResult::from_asset(
            local,
            &version,
            expected,
            digest.to_ascii_lowercase(),
            asset.size,
        );
        if result.has_update || show_current_notes {
            let body = self.body.unwrap_or_default();
            let visible: String = body.chars().take(12_000).collect();
            result.release_notes = notes::clean(&visible);
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn published(tag: &str) -> Release {
        let name = format!("KunKunIME-{}-Windows-x64.zip", tag.trim_start_matches('v'));
        Release {
            tag_name: tag.into(),
            draft: false,
            prerelease: false,
            body: Some("## 修复\n- 置顶".into()),
            assets: vec![Asset {
                browser_download_url: format!("{WINDOWS_RELEASES_PAGE}/download/{tag}/{name}"),
                name,
                state: "uploaded".into(),
                size: 42,
                digest: Some(format!("sha256:{}", "f".repeat(64))),
            }],
        }
    }

    fn one(release: Release, local: &str) -> Result<CheckResult, UpdateError> {
        let mut first = Some(release);
        check_pages(local, true, |_| Ok(vec![first.take().unwrap()]))
    }

    #[test]
    fn selects_highest_stable_windows_release_across_unordered_mixed_pages() {
        let result = check_pages("v0.1.9", true, |page| {
            if page == 1 {
                let mut records: Vec<_> = (0..PER_PAGE - 2)
                    .map(|_| published("android-v8.0.0"))
                    .collect();
                records.push(published("v0.1.2"));
                records.push(published("v0.1.9"));
                Ok(records)
            } else {
                assert_eq!(page, 2);
                let mut draft = published("v8.0.0");
                draft.draft = true;
                let mut preview = published("v4.0.0");
                preview.prerelease = true;
                Ok(vec![
                    draft,
                    preview,
                    published("v3.0.0-rc.1"),
                    published("v2.0.0+test"),
                    published("v0.1.10"),
                    published("v0.1.1"),
                ])
            }
        })
        .unwrap();
        assert!(result.has_update);
        assert_eq!(result.remote_version, "0.1.10");
        assert_eq!(
            result.release_page_url(),
            format!("{WINDOWS_RELEASES_PAGE}/tag/v0.1.10")
        );
        assert!(
            result
                .download_url
                .ends_with("/v0.1.10/KunKunIME-0.1.10-Windows-x64.zip")
        );
        assert_eq!(result.release_notes, "修复\n- 置顶");
    }

    #[test]
    fn current_and_newer_local_versions_skip_upgrading() {
        for local in ["v0.1.10", "v0.2.0"] {
            let result = one(published("v0.1.10"), local).unwrap();
            assert!(!result.has_update && !result.local_unparsable);
        }
        assert!(
            one(published("v0.1.10"), "devhash")
                .unwrap()
                .local_unparsable
        );
        assert!(one(published("android-v0.1.10"), "0.1.9").is_err());
    }

    #[test]
    fn incomplete_or_redirected_latest_zip_cannot_be_reported_as_current() {
        let mut release = published("v0.1.10");
        release.assets[0].name = "KunKunIME-Android-0.1.10.apk".into();
        assert!(one(release, "0.1.9").is_err());
        let mut release = published("v0.1.10");
        release.assets[0].browser_download_url = "https://example.org/other.zip".into();
        assert!(one(release, "0.1.9").is_err());
        let mut release = published("v0.1.10");
        release.assets[0].digest = None;
        assert!(one(release, "0.1.9").is_err());
        let mut release = published("v0.1.10");
        release.assets.extend(published("v0.1.10").assets);
        assert!(one(release, "0.1.9").is_err());
    }

    #[test]
    fn interrupted_pagination_keeps_the_network_error() {
        let result = check_pages("v0.1.10", false, |page| match page {
            1 => Ok((0..PER_PAGE).map(|_| published("v0.1.10")).collect()),
            _ => Err(UpdateError::Network("离线".into())),
        });
        assert_eq!(result.unwrap_err(), UpdateError::Network("离线".into()));
    }

    #[test]
    #[ignore = "queries the public GitHub Releases API"]
    fn live_public_windows_release() {
        let result = check("0.0.0", true).unwrap();
        assert!(result.has_update);
        println!(
            "Windows {}: {} ({} bytes)",
            result.remote_version, result.download_url, result.size
        );
    }
}
