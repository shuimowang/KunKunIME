#!/usr/bin/env python3
"""比对安卓设置清单与电脑版设置清单的共用项标题。

规则（docs/mobile-ux-reference.md §6.4 第 2 条）：共用项的标题照抄电脑版的 label，
电脑版改名时两边一起改。本脚本找出不一致的地方，免得两端慢慢说法不一样。

- 安卓清单：app/src/main/assets/data/settings_manifest.toml（source = "engine" 的项）
- 电脑版清单：../wind-setting/src/assets/settings_manifest.toml（[[items]] 的 key / label）

电脑版清单里没有的引擎键（如移动端专属的 mobile.association.*）只列出、不算错。
有标题不一致时退出码为 1。

用法：python3 scripts/check-settings-labels.py [电脑版清单路径]
"""

import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "app/src/main/assets/data/settings_manifest.toml"
DESKTOP = ROOT.parent / "wind-setting/src/assets/settings_manifest.toml"

# 电脑版没有、由手机端自己命名的引擎键（移动端覆盖键等）。新增时登记在这里并写明理由。
MOBILE_ONLY = {
    "mobile.association.kind": "移动端覆盖键，电脑版清单按设计不收（wind-setting 豁免名单 ⑳）",
    "mobile.association.mode": "同上",
    "mobile.association.punct": "同上",
    "mobile.association.space_commits": "同上",
}


def main() -> int:
    desktop_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DESKTOP
    if not desktop_path.is_file():
        print(f"找不到电脑版清单：{desktop_path}（两仓需并列存放）", file=sys.stderr)
        return 2
    android = tomllib.loads(ANDROID.read_text(encoding="utf-8"))
    desktop = tomllib.loads(desktop_path.read_text(encoding="utf-8"))
    labels = {it["key"]: it.get("label", "") for it in desktop.get("items", []) if "key" in it}

    mismatched, unknown = [], []
    for item in android.get("item", []):
        if item.get("source") != "engine":
            continue
        key, title = item["key"], item.get("title", "")
        if key not in labels:
            if key not in MOBILE_ONLY:
                unknown.append(key)
            continue
        if labels[key] != title:
            mismatched.append((key, title, labels[key]))

    for key, title, label in mismatched:
        print(f"标题不一致  {key}\n    安卓：{title}\n    电脑：{label}")
    for key in unknown:
        print(f"电脑版清单里没有（若是手机专属，登记进 MOBILE_ONLY）：{key}")
    if not mismatched and not unknown:
        print("共用项标题与电脑版一致")
    return 1 if mismatched or unknown else 0


if __name__ == "__main__":
    sys.exit(main())
