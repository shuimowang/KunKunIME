"""Rebuild the English spelling table and rank file from pinned, redistributable sources.

Only lexical statistics and spellings are stored in the product. Book archives
remain in the local build cache. Run from any directory with Python 3.10+.
"""
import argparse
from collections import Counter
import hashlib
from pathlib import Path, PurePosixPath
import re
import urllib.request
from xml.etree import ElementTree
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
CMU = "74790861f652b15e4ac49015a90074ad62a27690"
BOOKS = {
    "pride": ("standardebooks/jane-austen_pride-and-prejudice", "a77a2dbbc0d11a17d6c68c581f17e26a65bd8168"),
    "tom": ("standardebooks/mark-twain_the-adventures-of-tom-sawyer", "3daf72f4d3c3dd7d68476dc77235be6ffcf20621"),
    "expectations": ("standardebooks/charles-dickens_great-expectations", "a8de3e503018ef79147a06488b336fc5446fe7a4"),
    "rust": ("rust-lang/book", "5c23042f85595490ad9f7412c4c6f57fe0b330d5"),
}
# Hash member contents rather than ZIP metadata, which can vary between downloads.
CHAPTER_HASH = {
    "pride": "1972dffcf669f4477f16fe4fd82837b86474e31c9468f91aabcb1513caff0e26",
    "tom": "b1e8ea5c8477804e47f04aca8693ab58fc57ef971e8470005f7227e8a2aaf0b3",
    "expectations": "f34943bece96459297401e801eabf0d8166057480b723d14f5b7c2a5a4a27c15",
    "rust": "b81e07e36c49e0b272fad919cca853c710efe7e5852c362ca7932847acc56163",
}
# Product vocabulary for keyboard, messaging and development contexts. A few
# words preserve conventional case even if the spelling source is lower-case.
PREFERRED = """
I I'm I've I'll I'd English Monday Tuesday Wednesday Thursday Friday Saturday Sunday
January February March April May June July August September October November December
Python Java JavaScript TypeScript Rust Git GitHub GitLab Linux Windows Android macOS iOS
HTTPS HTTP API ASCII UTF Unicode JSON HTML CSS SQL XML YAML TOML CSV GUI CPU GPU USB URL
OpenAI ChatGPT ChatBot WiFi Bluetooth Markdown PowerShell Excel Word PDF ID UUID ZIP FTP
KunKunIME IME IDE IPC TSF SDK CLI DNS IPv4 IPv6 TCP IP RAM SSD AI IT npm Node.js C++ C#
""".split()
# Modern language absent or unrepresentative in the book corpus. These are
# editorial priority hints, not measured counts from another frequency list.
BOOST = {
    "hello": 450, "help": 360, "thank": 240, "thanks": 220, "please": 330,
    "world": 300, "welcome": 200, "email": 200, "message": 210, "keyboard": 200,
    "computer": 240, "network": 190, "software": 200, "server": 190,
    "browser": 180, "application": 130, "developer": 160, "development": 155,
    "document": 130, "download": 130, "upload": 120, "install": 130,
    "product": 130, "project": 160, "password": 100, "account": 140,
    "screen": 130, "setup": 80, "setting": 85, "settings": 100,
    "user": 130, "search": 145, "system": 145, "database": 140,
    "input": 125, "function": 110, "program": 115, "programming": 100,
    "login": 95, "library": 105, "release": 110, "emoji": 85, "autocomplete": 70,
}
for word in PREFERRED:
    BOOST.setdefault(word.lower(), 65)
for family in """
template templates; file files filing; folder folders; directory directories; open opening opened
save saving saved; close closing closed; create creating created creation; delete deleting deleted deletion
rename renaming renamed; select selection selected; edit editing edited editor editors; update updates updating
print printing printer printers; copy copying copied; paste pasting pasted; remove removal removed
insert insertion inserted; replace replacement replaced; attach attached attachment attachments
send sending sent sender; receive received receiving receiver; share sharing shared; link links linking
reply replies; report reports reporting; plan plans planning; note notes notebook notebooks
meeting meetings; schedule scheduled scheduling; calendar calendars; event events; reminder reminders
contact contacts; team teams teamwork; task tasks; workflow workflows; issue issues; review reviews
confirm confirmation confirmed; request requests requested; submit submission submissions
cancel canceled cancelling; search searches searching; find finding found; filter filters filtering
sort sorted sorting; field fields; label labels; value values; option options optional
table tables; column columns; row rows; list lists; sheet sheets spreadsheet spreadsheets
screen screens screenshot screenshots; page pages; window windows; dialog dialogs; panel panels
display displays; image images; photo photos; picture pictures; video videos; audio; clipboard
font fonts; style styles styling; format formats formatting; default defaults; preview previews
menu menus; button buttons; shortcut shortcuts; profile profiles; language languages; dictionary dictionaries
network networks; connection connections; device devices; service services; permission permissions
security secure; privacy private; online offline; sync synchronized; access accessible accessibility
package packages; version versions; feature features; bug bugs; launch launched; deploy deployment
test tests testing tested; build builds building; design designs; code coding; developer developers
cloud storage; internet; website websites; page pages; cache cached caching; queue queued
""".strip().splitlines():
    for phrase in family.split(";"):
        for position, term in enumerate(phrase.split()):
            BOOST.setdefault(term, max(20, 125 - position * 30))
TOKEN = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*")


def fetch(path, url, expected=None):
    if not path.is_file():
        req = urllib.request.Request(url, headers={"User-Agent": "KunKunIME English dictionary build"})
        with urllib.request.urlopen(req, timeout=120) as source:
            data = source.read()
        if expected and hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Unexpected download content for {path.name}")
        path.write_bytes(data)
    if expected and hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f"Unexpected cached content for {path.name}")
    return path


def build(cache, destination):
    cache.mkdir(parents=True, exist_ok=True)
    destination.mkdir(parents=True, exist_ok=True)
    raw = fetch(cache / "cmudict.dict", f"https://raw.githubusercontent.com/cmusphinx/cmudict/{CMU}/cmudict.dict",
                "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22")
    words = set()
    for line in raw.read_text(encoding="latin1").splitlines():
        word = re.sub(r"\(\d+\)$", "", line.split()[0]).lower()
        if TOKEN.fullmatch(word):
            words.add(word)
    frequencies = Counter()
    for key, (repo, rev) in BOOKS.items():
        archive = fetch(cache / f"{key}.zip", f"https://codeload.github.com/{repo}/zip/{rev}")
        with ZipFile(archive) as z:
            if key == "rust":
                chapters = [n for n in z.namelist() if "/src/ch" in n and n.endswith(".md") and len(PurePosixPath(n).parts) == 3]
            else:
                chapters = [n for n in z.namelist() if "/src/epub/text/chapter-" in n and n.endswith(".xhtml")]
            fingerprint = hashlib.sha256()
            for name in sorted(chapters):
                content = z.read(name)
                fingerprint.update(name.split("/", 1)[1].encode("utf8") + b"\0" + hashlib.sha256(content).digest())
                if key == "rust":
                    prose = re.sub(r"```.*?```|<!--.*?-->", "", content.decode("utf8"), flags=re.S)
                else:
                    xml = ElementTree.fromstring(content)
                    prose = " ".join("".join(p.itertext()) for p in xml.iter("{http://www.w3.org/1999/xhtml}p"))
                frequencies.update(token.lower() for token in TOKEN.findall(prose.replace("’", "'")))
            if fingerprint.hexdigest() != CHAPTER_HASH[key]:
                raise ValueError(f"Unexpected chapter contents in {key}")
    words.update(BOOST)
    ranks = sorted((w for w in words if frequencies[w] or w in BOOST),
                   key=lambda w: (-(frequencies[w] + BOOST.get(w, 0)), w))
    proper = {w.lower(): w for w in PREFERRED}
    header = "# KunKunIME English dictionary\n# Source and licences: licenses/THIRD-PARTY.md\n---\nname: en\nversion: \"2026.11.22\"\nsort: by_weight\n...\n"
    (destination / "en.dict.yaml").write_text(header + "".join(f"{proper.get(w, w)}\t{w}\n" for w in sorted(words)),
                                             encoding="utf8", newline="\n")
    (destination / "en_freq.txt").write_text("# KunKunIME English prefix ranking. See licenses/THIRD-PARTY.md.\n" +
                                             "\n".join(ranks) + "\n", encoding="utf8", newline="\n")
    print(f"{len(words)} spellings; {len(ranks)} ranked words")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cache", type=Path, default=ROOT / "artifacts/public-sources")
    p.add_argument("--out", type=Path, default=ROOT / "WindInput/data/schemas/english")
    args = p.parse_args()
    build(args.cache, args.out)

