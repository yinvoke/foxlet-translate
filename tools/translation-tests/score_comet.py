"""Compute full-corpus COMET deltas by cancelling identical output pairs."""

import argparse, json, pathlib, hashlib, importlib.metadata, time
from inputs import verify
import numpy as np


def main():
    import torch
    from comet import load_from_checkpoint
    from pytorch_lightning import seed_everything

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--work-dir",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[2]
        / "build/translation-quality",
    )
    out = parser.parse_args().work_dir
    verify(out)
    torch.set_num_threads(6)
    seed_everything(20260914, workers=True)
    checkpoint = next(
        (
            pathlib.Path.home()
            / ".cache/huggingface/hub/models--Unbabel--wmt22-comet-da/snapshots"
        ).glob("*/checkpoints/model.ckpt")
    )
    assert (
        hashlib.sha256(checkpoint.read_bytes()).hexdigest()
        == "e213091cde220f97b89f8bdfa750c458cfea741ad62affb455b59900210ff2af"
    )
    model = load_from_checkpoint(str(checkpoint), local_files_only=True)
    result = []
    rng = np.random.default_rng(20260914)
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
        paths = {
            label: out / "outputs" / f"{direction}.single.{label}.txt"
            for label in ["legacy", "current"]
        }
        source = (out / "data" / f"{src}.single.txt").read_text().splitlines()
        refs = (out / "data" / f"{trg}.single.txt").read_text().splitlines()
        texts = {label: p.read_text().splitlines() for label, p in paths.items()}
        n = len(source)
        assert n == 1012 and len(refs) == n and all(len(v) == n for v in texts.values())
        changed = [i for i in range(n) if texts["legacy"][i] != texts["current"][i]]
        samples = [
            {"src": source[i], "mt": texts[label][i], "ref": refs[i]}
            for i in changed
            for label in ["legacy", "current"]
        ]
        # Check no input gets truncated by COMET's encoder (including special tokens).
        max_tokens = max(
            [
                len(model.encoder.tokenizer.encode(s[k], add_special_tokens=False))
                for s in samples
                for k in ["src", "mt", "ref"]
            ],
            default=0,
        )
        assert (
            max_tokens + model.encoder.tokenizer.num_special_tokens_to_add()
            <= model.encoder.max_positions - 2
        ), (direction, max_tokens, model.encoder.max_positions)
        start = time.monotonic()
        scores = (
            model.predict(
                samples, batch_size=8, gpus=0, num_workers=2, progress_bar=False
            ).scores
            if samples
            else []
        )
        pairs = [
            {
                "row": i + 1,
                "legacy": scores[2 * j] * 100,
                "current": scores[2 * j + 1] * 100,
                "delta": (scores[2 * j + 1] - scores[2 * j]) * 100,
            }
            for j, i in enumerate(changed)
        ]
        delta = np.zeros(n)
        for p in pairs:
            delta[p["row"] - 1] = p["delta"]
        bootstrap = [delta[rng.integers(0, n, n)].mean() for _ in range(2000)]
        item = {
            "direction": direction,
            "n": n,
            "changed_count": len(changed),
            "comet_x100_mean_delta": float(delta.mean()),
            "bootstrap_95ci": np.percentile(bootstrap, [2.5, 97.5]).tolist(),
            "max_content_tokens": max_tokens,
            "pairs": pairs,
            "elapsed_seconds": time.monotonic() - start,
        }
        result.append(item)
        summary = {
            "metric": "Unbabel/wmt22-comet-da x100",
            "method": "Evaluate both outputs of every changed single row; exact matches have zero paired delta. Mean delta uses ALL 1012 rows, not only changed rows. No absolute system score was computed. No paragraph COMET.",
            "checkpoint_sha256": hashlib.sha256(checkpoint.read_bytes()).hexdigest(),
            "checkpoint_revision": checkpoint.parent.parent.name,
            "device": "CPU",
            "threads": 6,
            "batch_size": 8,
            "num_workers": 2,
            "seed": 20260914,
            "versions": {
                p: importlib.metadata.version(p)
                for p in [
                    "unbabel-comet",
                    "torch",
                    "transformers",
                    "pytorch-lightning",
                    "tokenizers",
                    "sacrebleu",
                    "numpy",
                ]
            },
            "results": result,
        }
        (out / "comet-scores.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(
            direction,
            "changed",
            len(changed),
            "delta",
            item["comet_x100_mean_delta"],
            "seconds",
            item["elapsed_seconds"],
            flush=True,
        )


if __name__ == "__main__":
    main()
