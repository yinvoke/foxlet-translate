"""Score the complete paired translation matrix; sacrebleu is version-pinned."""

import argparse, json, pathlib, re, importlib.metadata
from inputs import verify
import numpy as np
from sacrebleu.metrics import CHRF, BLEU

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--work-dir",
    type=pathlib.Path,
    default=pathlib.Path(__file__).resolve().parents[2] / "build/translation-quality",
)
out = parser.parse_args().work_dir
verify(out)
rng = np.random.default_rng(20260914)
result = []
for direction in [
    "en-zh",
    "zh-en",
    "ja-en",
    "ko-en",
    "ru-en",
    "ja-zh",
    "ko-zh",
    "ru-zh",
]:
    src, trg = direction.split("-")
    for shape in ["single", "paragraph"]:
        texts = {
            label: (out / "outputs" / f"{direction}.{shape}.{label}.txt")
            .read_text()
            .splitlines()
            for label in ["legacy", "current"]
        }
        refs = (out / "data" / f"{trg}.{shape}.txt").read_text().splitlines()
        n = len(refs)
        assert all(len(v) == n and all(v) for v in texts.values())
        metric = CHRF(word_order=2)
        bleu = BLEU(tokenize="zh" if trg == "zh" else "13a")
        stats = {
            label: np.array(
                metric._extract_corpus_statistics(hyps, [refs]), dtype=np.float64
            )
            for label, hyps in texts.items()
        }
        summary = {
            label: {
                "chrfpp": metric.corpus_score(hyps, [refs]).score,
                "bleu": bleu.corpus_score(hyps, [refs]).score,
            }
            for label, hyps in texts.items()
        }
        deltas = []
        for _ in range(2000):
            idx = rng.integers(0, n, n)
            s = {
                label: metric._compute_score_from_stats(
                    v[idx].sum(axis=0).tolist()
                ).score
                for label, v in stats.items()
            }
            deltas.append(s["current"] - s["legacy"])
        changed = [
            i
            for i, (a, b) in enumerate(zip(texts["legacy"], texts["current"]))
            if a != b
        ]
        counts = {}
        for label in texts:
            log = (out / "outputs" / f"{direction}.{shape}.{label}.log").read_text()
            counts[label] = [
                int(m)
                for m in re.findall(r"^\[ssplit\] line=\d+ sentences=(\d+)$", log, re.M)
            ]
            assert len(counts[label]) == n
        item = {
            "direction": direction,
            "shape": shape,
            "n": n,
            "scores": summary,
            "chrfpp_delta": summary["current"]["chrfpp"] - summary["legacy"]["chrfpp"],
            "bleu_delta": summary["current"]["bleu"] - summary["legacy"]["bleu"],
            "chrfpp_delta_bootstrap_95ci": np.percentile(deltas, [2.5, 97.5]).tolist(),
            "changed_rows": [i + 1 for i in changed],
            "changed_count": len(changed),
            "source_segment_count_changed": sum(
                a != b for a, b in zip(counts["legacy"], counts["current"])
            ),
            "source_segments": {k: sum(v) for k, v in counts.items()},
            "chrfpp_signature": str(metric.get_signature()),
            "bleu_signature": str(bleu.get_signature()),
        }
        result.append(item)
        print(
            direction,
            shape,
            "changed",
            len(changed),
            "delta",
            round(item["chrfpp_delta"], 4),
            item["chrfpp_delta_bootstrap_95ci"],
            flush=True,
        )
(out / "scores.json").write_text(
    json.dumps(
        {
            "sacrebleu_version": importlib.metadata.version("sacrebleu"),
            "bootstrap": {
                "seed": 20260914,
                "samples": 2000,
                "unit": "input row; paired percentile; single rows within source articles are not independent; approximate intervals, not a noninferiority test",
            },
            "results": result,
        },
        indent=2,
    )
    + "\n"
)
