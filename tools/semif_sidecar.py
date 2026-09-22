#!/usr/bin/env python3
"""Long-lived SemIf scorer for the kNES agent: one decision per line, in and out.

SemIf ships a batch CLI (`semif-score`, a JSONL file in and a new JSONL file out), which
loads the model on every invocation. A turn-by-turn agent cannot pay nine seconds of
model load per decision, so this keeps one process alive: load once, then read one
decision row per line of stdin and write one result per line of stdout.

Protocol, line-delimited JSON both ways:

    -> {"id": "...", "state": ..., "question": "...", "options": [{"id","description"}]}
    <- {"id": "...", "option_ids": [...], "probabilities": [...], "ms": 121}

The first line out is {"ready": true, ...} once the model is resident. A row that fails
validation or scoring answers {"id": ..., "error": "..."} and the process stays up — one
bad decision must not take the agent down mid-run. Diagnostics go to stderr, never to
stdout, which carries nothing but protocol.

Usage:
    python3 tools/semif_sidecar.py --model Qwen/Qwen3.5-4B --revision <40-hex> \
        [--backend mlx|torch] [--bits 4|8] [--semif-src /path/to/SemIf/src]
"""

from __future__ import annotations

import argparse
import json
import sys
import time


def emit(payload: dict) -> None:
    """One JSON object per stdout line, flushed — the reader blocks on the newline."""
    sys.stdout.write(json.dumps(payload, allow_nan=False) + "\n")
    sys.stdout.flush()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="checkpoint id or local directory")
    parser.add_argument("--revision", required=True, help="pinned 40-hex commit, or a label for a local directory")
    parser.add_argument("--backend", choices=("mlx", "torch"), default="mlx")
    parser.add_argument("--bits", type=int, choices=(4, 8), help="MLX in-memory quantization")
    parser.add_argument("--device", choices=("auto", "cuda", "mps"), default="auto", help="torch backend only")
    parser.add_argument("--dtype", choices=("bfloat16", "float16", "float32"), default="bfloat16", help="torch backend only")
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--semif-src", help="SemIf src/ directory, when semif_phase1 is not installed in this interpreter")
    args = parser.parse_args()

    if args.semif_src:
        sys.path.insert(0, args.semif_src)

    try:
        from semif_phase1.core import validate_row
    except ImportError as error:
        # Say how to fix it here rather than letting a bare traceback reach the agent log.
        print(f"semif_phase1 is not importable: {error}. Install SemIf into this interpreter "
              f"or pass --semif-src /path/to/SemIf/src.", file=sys.stderr)
        return 2

    started = time.perf_counter()
    if args.backend == "mlx":
        from semif_phase1 import mlx_backend

        model, tokenizer, metadata = mlx_backend.load_model(args.model, args.revision, args.bits)
        score = mlx_backend.score
    else:
        from semif_phase1.core import load_causal_model
        from semif_phase1.direct import score as direct_score

        model, tokenizer, metadata = load_causal_model(args.model, args.revision, args.device, args.dtype)
        score = direct_score

    emit({
        "ready": True,
        "model": args.model,
        "backend": args.backend,
        "bits": args.bits,
        "load_seconds": round(time.perf_counter() - started, 2),
    })

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        row_id = "(unparsed)"
        try:
            row = json.loads(line)
            row_id = row.get("id", row_id)
            validate_row(row)
            call = time.perf_counter()
            result = score(model, tokenizer, row, metadata, args.max_tokens)
            emit({
                "id": result["id"],
                "option_ids": result["option_ids"],
                "probabilities": result["probabilities"],
                "ms": round((time.perf_counter() - call) * 1000),
            })
        except Exception as error:  # one bad row must not end the run
            emit({"id": row_id, "error": f"{type(error).__name__}: {error}"})
    return 0


if __name__ == "__main__":
    sys.exit(main())
