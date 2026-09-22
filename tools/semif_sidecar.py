#!/usr/bin/env python3
"""Long-lived SemIf scorer for the kNES agent: one decision per line, in and out.

SemIf ships a batch CLI (`semif-score`, a JSONL file in and a new JSONL file out), which
loads the model on every invocation. A turn-by-turn agent cannot pay nine seconds of
model load per decision, so this keeps one process alive: load once, then read one
decision row per line of stdin and write one result per line of stdout.

Protocol, line-delimited JSON both ways:

    -> {"id": "...", "state": ..., "question": "...", "options": [{"id","description"}],
        "image": "<base64 png>"}                          # image only on the pixel backend
    <- {"id": "...", "option_ids": [...], "probabilities": [...], "ms": 121}

`--backend pixels` is the same readout with the screen itself as the evidence: the pinned
checkpoint is a vision-language model whose ViT the text backend drops, so loading the
whole thing through mlx-vlm and taking the logits at the same answer slots gives a typed
decision made from what is on screen. Measured on an M-series Mac at 256x240: **187 ms**.
It is the difference between an agent that reads a RAM digest and one that can see a pipe.

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
from pathlib import Path


def emit(payload: dict) -> None:
    """One JSON object per stdout line, flushed — the reader blocks on the newline."""
    sys.stdout.write(json.dumps(payload, allow_nan=False) + "\n")
    sys.stdout.flush()


LETTERS = "ABCDEFGHIJKLMNOP"

PIXEL_SYSTEM = ("Apply the supplied criterion to the supplied evidence. Choose exactly one listed option. "
                "Respond with only its uppercase letter, with no explanation or reasoning.")


def pixel_scorer(args):
    """The same readout, with the screen as the evidence.

    SemIf's own MLX backend keeps only the text half of the checkpoint. The pinned
    Qwen3.5-4B is a vision-language model whose ViT weights are already in the cache, so
    loading the whole thing through mlx-vlm and taking the logits at the declared answer
    slots is the identical interface with a screenshot in place of a paragraph. Adapted
    from SemIf's own `doom_pixels_semif.py`.

    Returns a `score(model, tokenizer, row, metadata, max_tokens)` callable so the loop
    above does not care which backend it is talking to.
    """
    import base64
    import io
    import math

    import mlx.core as mx
    from PIL import Image
    from mlx_vlm import load
    from mlx_vlm.prompt_utils import apply_chat_template
    from mlx_vlm.utils import prepare_inputs

    source = args.model if Path(args.model).is_dir() else snapshot_path(args.model, args.revision)
    model, processor = load(source, trust_remote_code=False)
    config = model.config
    tok = processor.tokenizer if hasattr(processor, "tokenizer") else processor
    size = (args.image_width, args.image_height)

    def softmax(values):
        top = max(values)
        exponentials = [math.exp(v - top) for v in values]
        total = sum(exponentials)
        return [e / total for e in exponentials]

    def score(_model, _tokenizer, row, _metadata, _max_tokens):
        encoded = row.get("image")
        if not encoded:
            raise ValueError("the pixels backend needs an 'image': the screen is the evidence")
        picture = Image.open(io.BytesIO(base64.b64decode(encoded))).convert("RGB").resize(size)
        options = row["options"]
        payload = {
            "evidence": row["state"],
            "criterion": row["question"],
            "options": [{"letter": LETTERS[i], "description": o["description"]} for i, o in enumerate(options)],
        }
        messages = [
            {"role": "system", "content": PIXEL_SYSTEM},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ]
        prompt = apply_chat_template(processor, config, messages, num_images=1)
        inputs = prepare_inputs(processor, images=[picture], prompts=[prompt],
                                image_token_index=getattr(config, "image_token_id", None))
        slots = [tok.encode(LETTERS[i], add_special_tokens=False)[0] for i in range(len(options))]
        extra = {k: v for k, v in inputs.items() if k not in ("input_ids", "pixel_values", "attention_mask")}
        out = model(inputs["input_ids"], inputs.get("pixel_values"), mask=inputs.get("attention_mask"), **extra)
        logits = out.logits if hasattr(out, "logits") else out
        selected = logits[0, -1].astype(mx.float32)[mx.array(slots)]
        mx.eval(selected)
        return {
            "id": row["id"],
            "option_ids": [o["id"] for o in options],
            "probabilities": softmax(selected.tolist()),
        }

    return score


def snapshot_path(source: str, revision: str) -> str:
    """Where the pinned checkpoint already sits.

    Cache first: the weights are the same ones the text backend uses, and reaching for the
    network asks the Hub for files the model does not need (a LICENSE, among others) — which
    fails outright once a stored token has expired, for a checkpoint already on the disk.
    """
    from huggingface_hub import snapshot_download

    try:
        return snapshot_download(source, revision=revision, local_files_only=True)
    except Exception:
        return snapshot_download(
            source, revision=revision,
            allow_patterns=["*.json", "model*.safetensors", "*.jinja", "*.txt", "*.model"],
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="checkpoint id or local directory")
    parser.add_argument("--revision", required=True, help="pinned 40-hex commit, or a label for a local directory")
    parser.add_argument("--backend", choices=("mlx", "torch", "pixels"), default="mlx")
    parser.add_argument("--image-width", type=int, default=256, help="pixels backend: width the frame is scaled to")
    parser.add_argument("--image-height", type=int, default=240, help="pixels backend: height the frame is scaled to")
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
    if args.backend == "pixels":
        score = pixel_scorer(args)
        model = tokenizer = metadata = None
    elif args.backend == "mlx":
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
