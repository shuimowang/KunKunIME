use std::time::{SystemTime, UNIX_EPOCH};
use wind_dict::cached::CachedDict;
use wind_dict::datformat::WdatWriter;
use wind_engine::pinyin::{
    dag::{Dag, SegGraph},
    lattice::LatticeBuilder,
    syllable::SyllableTrie,
};

#[test]
fn rare_pairs_back_off_only_at_a_competing_word_boundary() {
    let dir = std::env::temp_dir().join(format!(
        "wind-crossing-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir(&dir).unwrap();
    let file = dir.join("test.wdat");
    let mut writer = WdatWriter::new();
    for (code, text, freq, boundary) in [
        ("pi", "皮", 32_000, 1),
        ("bao", "包", 18_000, 1),
        ("guan", "关", 20_000, 1),
        ("zi", "子", 30_000, 1),
        ("pibao", "皮包", 20, 1 | (1 << 2)),
        ("baoguan", "保管", 8_000, 1 | (1 << 3)),
        ("guanzi", "关子", 20, 1 | (1 << 4)),
    ] {
        writer.add_with_boundary(code.into(), vec![(text.into(), freq, 0, boundary)]);
    }
    writer.write(&file).unwrap();
    let dict = CachedDict::load_at(&dir.join("test.yaml"), &file).unwrap();
    let score = |code: &str, text: &str| {
        let graph = SegGraph::from_dag(&Dag::build(code, &SyllableTrie::new()));
        LatticeBuilder::new()
            .build(code, &graph, &dict, None, true)
            .into_iter()
            .flatten()
            .find(|node| node.word == text)
            .unwrap()
            .log_prob
    };
    let overlap = "pibaoguanzile";
    assert!(score(overlap, "皮包") < score("pibaole", "皮包"));
    assert!(score(overlap, "关子") < score("guanzile", "关子"));
    assert_eq!(score("pibao", "皮包"), score("pibaole", "皮包"));
    assert_eq!(score("baoguanzi", "关子"), score("guanzi", "关子"));
    assert_eq!(score(overlap, "关"), score("guan", "关"));
    drop(dict);
    std::fs::remove_dir_all(dir).unwrap();
}
