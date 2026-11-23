//! 校验词库管理窗口手动输入的补全。自动学习仍沿用对应输入模式的准入规则。
use anyhow::{Result, bail, ensure};
use url::{Host, Url};
use wind_store::completion::{COMPLETION_TEXT_MAX_CHARS, CompletionKind};

pub(super) fn normalize(kind: CompletionKind, input: &str) -> Result<String> {
    ensure!(
        !input.chars().any(char::is_control),
        "补全内容只支持单行文字"
    );
    let text = input.trim();
    ensure!(!text.is_empty(), "请输入补全内容");
    let result = match kind {
        CompletionKind::EmailSuffix => normalize_email_suffix(text)?,
        CompletionKind::UrlHistory => normalize_url(text)?,
        CompletionKind::QuickHistory => text.to_owned(),
    };
    ensure!(
        result.chars().count() <= COMPLETION_TEXT_MAX_CHARS,
        "补全内容最多 {COMPLETION_TEXT_MAX_CHARS} 个字符"
    );
    Ok(result)
}

fn normalize_email_suffix(text: &str) -> Result<String> {
    const INVALID: &str = "请输入有效的邮箱域名或完整邮箱，例如 example.com 或 name@example.com";
    let domain = match text.split_once('@') {
        Some(("", domain)) => domain,
        Some((local, domain)) => {
            ensure!(
                !local.starts_with('.')
                    && !local.ends_with('.')
                    && !local.contains("..")
                    && local
                        .chars()
                        .all(|c| { c.is_alphanumeric() || ".!#$%&'*+-/=?^_`{|}~".contains(c) }),
                INVALID
            );
            domain
        }
        None => text,
    };
    ensure!(
        !domain.contains(['@', '/', '\\', ':', '?', '#', '%'])
            && !domain.chars().any(char::is_whitespace),
        INVALID
    );
    let Ok(Host::Domain(host)) = Host::parse(domain) else {
        bail!(INVALID);
    };
    ensure!(valid_domain(&host), INVALID);
    Ok(host)
}

fn valid_domain(host: &str) -> bool {
    host.len() <= 253
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
}

fn normalize_url(text: &str) -> Result<String> {
    ensure!(
        !text.contains('\\') && !text.chars().any(char::is_whitespace),
        "网址不能含空格或反斜杠"
    );
    let explicit = text.contains("://");
    let input = if explicit {
        text.to_owned()
    } else {
        format!("https://{text}")
    };
    let parsed = Url::parse(&input)
        .map_err(|_| anyhow::anyhow!("请输入有效网址，例如 https://example.com/docs"))?;
    ensure!(
        matches!(parsed.scheme(), "http" | "https"),
        "请输入 HTTP 或 HTTPS 网址"
    );
    let host = parsed
        .host()
        .ok_or_else(|| anyhow::anyhow!("网址缺少主机名"))?;
    if let Host::Domain(name) = host {
        ensure!(valid_domain(name), "网址的主机名无效");
        ensure!(
            explicit || name.contains('.') || name == "localhost",
            "请输入完整域名，例如 example.com"
        );
    }
    // 仅规范化协议和主机，保留路径/查询的大小写及原来的末尾斜线。
    let mut url = parsed.as_str();
    if parsed.path() == "/" && !input.split_once("://").unwrap().1.contains('/') {
        url = url.strip_suffix('/').unwrap_or(url);
    }
    // www. 本身就是默认的网址模式引导。其它裸域名补协议，确保能按 https 前缀找回。
    if !explicit
        && text
            .get(..4)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case("www."))
    {
        url = url.strip_prefix("https://").unwrap();
    }
    Ok(url.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_manual_completion_input() {
        use CompletionKind::{EmailSuffix, QuickHistory, UrlHistory};
        for input in [
            " Example.ORG ",
            " @Example.ORG ",
            " first.last+news@Example.ORG ",
            "用户@Example.ORG",
        ] {
            assert_eq!(normalize(EmailSuffix, input).unwrap(), "example.org");
        }
        assert_eq!(
            normalize(EmailSuffix, "笔名@例子.中国").unwrap(),
            normalize(EmailSuffix, "例子.中国").unwrap()
        );
        for bad in [
            "@",
            "reader@",
            "@@example.org",
            "a@b@example.org",
            "first last@example.org",
            "reader@ example.org",
            "reader@example .org",
            ".reader@example.org",
            "first..last@example.org",
            "reader.@example.org",
            "mailto:reader@example.org",
            "Reader <reader@example.org>",
            "reader@example.org/path",
            "reader@%65xample.org",
            "a..com",
            "-a.example",
            "a.example/x",
            "a\nb.org",
        ] {
            assert!(normalize(EmailSuffix, bad).is_err(), "{bad}");
        }
        for (input, expected) in [
            (
                "docs.Example.org/ReadMe?Q=A",
                "https://docs.example.org/ReadMe?Q=A",
            ),
            ("WWW.Example.org", "www.example.org"),
            ("http://example.org/", "http://example.org/"),
            ("HTTP://localhost:8080", "http://localhost:8080"),
        ] {
            assert_eq!(normalize(UrlHistory, input).unwrap(), expected);
        }
        for bad in [
            "words",
            "some words",
            "https://",
            "https://-bad.com",
            "data://example.com",
        ] {
            assert!(normalize(UrlHistory, bad).is_err(), "{bad}");
        }
        assert_eq!(
            normalize(QuickHistory, " 请查收附件！ ").unwrap(),
            "请查收附件！"
        );
        assert_eq!(normalize(QuickHistory, "git status").unwrap(), "git status");
        assert!(normalize(QuickHistory, "a\tb").is_err());
        assert!(normalize(QuickHistory, &"a".repeat(513)).is_err());
    }
}
