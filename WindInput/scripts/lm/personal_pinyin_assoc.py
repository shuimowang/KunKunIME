"""Build the personal desktop Pinyin next-word table from the bundled model."""

from pathlib import Path


def prefer_words(context, successors):
    # Single-character contexts are ambiguous. Keep the model's ordinary and
    # character-by-character rows intact. On whole-word contexts, keep its most
    # likely non-punctuation continuation and promote up to three whole words
    # already among its top eight suggestions. Preserve all original options.
    if len(context) < 2 or context.startswith("~"):
        return successors
    lexical = [i for i, text in enumerate(successors[:8])
               if sum(ch.isalnum() for ch in text) >= 2][:3]
    if not lexical:
        return successors
    first = next(i for i, text in enumerate(successors) if any(ch.isalnum() for ch in text))
    front = [first, *(i for i in lexical if i != first)]
    return [successors[i] for i in front] + [
        text for i, text in enumerate(successors) if i not in front
    ]


def main():
    root = Path(__file__).resolve().parents[2]
    source = root / "data/assoc/assoc_bigram.txt"
    target = root / "data_custom/assoc/assoc_bigram.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# 个人拼音后继表，由 scripts/lm/personal_pinyin_assoc.py 从出厂表调整展示次序。",
        "# 多字上文保留模型首个非标点后继，从模型前八项中优先展示至多三个多字词；原候选全部保留。",
        "# 下列注释记录原模型的来源、许可与生成参数。",
    ]
    changed = 0
    for line in source.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            context, words = line.split("\t", 1)
            original = words.split()
            ordered = prefer_words(context, original)
            changed += ordered != original
            line = context + "\t" + " ".join(ordered)
        lines.append(line)
    target.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    print(f"Adjusted {changed} contexts in {target}")


if __name__ == "__main__":
    main()
