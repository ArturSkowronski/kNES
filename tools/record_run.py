#!/usr/bin/env python3
"""Turn a finished run into a video, in the same windows the live view uses.

The point of the picture is the part the game does not show: which goals could run, how the
model ranked them, and which button came out. So every frame carries the screen on the left
and the decision on the right, laid out the way Final Fantasy lays out a screen — a window
per kind of thing.

Nothing here knows which game it is watching. The rows under STAN come from the same
`agent.state` block in `profiles/<id>.json` that the agent itself reads, so Final Fantasy
shows gold and a menu cursor while Super Mario Bros shows lives, coins and the world.

    tools/record_run.py ~/.knes/runs/latest --out mario.mp4
    tools/record_run.py ~/.knes/runs/latest --from 145 --to 220 --out clip.gif
    tools/record_run.py run-a run-b --out both.mp4        # several runs, one video

`.mp4` needs ffmpeg on the path; `.gif` does not.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

SCALE, NES = 2, (256, 240)
PANEL, MARGIN, GAP = 348, 14, 14
RANK_ROWS, RANK_H, STAT_H = 5, 34, 24
KOMENDA_H = 44 + RANK_ROWS * RANK_H
PAD_H = 150
STAT_ROWS = 8
STAN_H = 44 + STAT_ROWS * STAT_H + 26
PANEL_H = KOMENDA_H + GAP + PAD_H + GAP + STAN_H
WIDTH = MARGIN + NES[0] * SCALE + GAP + PANEL + MARGIN
HEIGHT = MARGIN + max(NES[1] * SCALE, PANEL_H) + MARGIN

GROUND, EDGE, INK, DIM = "#000000", "#f8f8f8", "#f8f8f8", "#9c9c9c"
GOLD, BLUE, GREEN = "#f8b800", "#0058f8", "#58d854"

DPAD = {"Up": (1, 0, "^"), "Left": (0, 1, "<"), "Right": (2, 1, ">"), "Down": (1, 2, "v")}
FACE = (("B", 40), ("A", 40), ("START", 66))


def _font(size, bold=False):
    for path in ("/System/Library/Fonts/Menlo.ttc", "/System/Library/Fonts/SFNSMono.ttf"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size, index=1 if bold and path.endswith("ttc") else 0)
            except Exception:
                pass
    return ImageFont.load_default()


F, FB, FS, FT = _font(15), _font(15, True), _font(12), _font(11, True)


def profile_state(profile_id):
    """The game's own state lines, from the profile the agent reads."""
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "knes-debug", "src", "main", "resources", "profiles", f"{profile_id}.json")
    if not os.path.exists(path):
        return []
    agent = json.load(open(path)).get("agent", {})
    return agent.get("state", [])


def render_state(lines, ram):
    """Mirrors ProfileAgent.stateLines: a labelled number, or a sentence chosen by value."""
    out = []
    for line in lines:
        value = ram.get(line["field"])
        if value is None:
            continue
        for i, name in enumerate(line.get("plusBytes", [])):
            value += (ram.get(name) or 0) << (8 * (i + 1))
        say = line.get("say")
        if say:
            text = say.get(str(value)) or say.get("*")
            if text:
                out.append((text, ""))
            continue
        out.append((line.get("label", line["field"]), value))
    return out


def ellipsize(draw, text, room):
    """Cut a label to the width it has, rather than letting it run over the number."""
    if room <= 0 or draw.textlength(text, font=F) <= room:
        return text
    while text and draw.textlength(text + "\u2026", font=F) > room:
        text = text[:-1]
    return text.rstrip() + "\u2026"


def window(draw, box, title=None):
    """A Final Fantasy window: white frame, black gutter, thin white edge inside."""
    x0, y0, x1, y1 = box
    draw.rectangle([x0, y0, x1, y1], fill=GROUND, outline=EDGE, width=4)
    draw.rectangle([x0 + 6, y0 + 6, x1 - 6, y1 - 6], outline=EDGE, width=2)
    if title:
        draw.text((x0 + 16, y0 + 15), title, font=FT, fill=GOLD)
    return x0 + 16, y0 + (34 if title else 16), x1 - 16


def ranking_for(run, turn):
    """The full ranking the goal selector dumped for this turn, best first."""
    path = os.path.join(run, "prompts", f"T{turn:05d}-executor-goals.txt")
    if not os.path.exists(path):
        return []
    out, section = [], None
    for line in open(path).read().splitlines():
        if line.startswith("=== "):
            section = line.strip("= ").strip().lower()
            continue
        if section == "ranking":
            parts = line.rsplit(" ", 1)
            if len(parts) == 2:
                try:
                    out.append((parts[0], float(parts[1])))
                except ValueError:
                    pass
    return out


def compose(shot, decision, ranking, state_lines, caption):
    img = Image.new("RGB", (WIDTH, HEIGHT), GROUND)
    d = ImageDraw.Draw(img)

    box = (MARGIN, MARGIN, MARGIN + NES[0] * SCALE + 8, MARGIN + NES[1] * SCALE + 8)
    d.rectangle(box, fill=GROUND, outline=EDGE, width=4)
    img.paste(Image.open(shot).convert("RGB").resize((NES[0] * SCALE, NES[1] * SCALE), Image.NEAREST),
              (box[0] + 4, box[1] + 4))
    if caption:
        d.text((box[0] + 6, box[3] + 10), caption, font=FT, fill=GOLD)

    ex = decision.get("executor", {}) or {}
    ram = decision.get("ram", {}) or {}
    pressed = {b.strip() for b in (ex.get("args", {}) or {}).get("buttons", "").split(",") if b.strip()}
    px = box[2] + GAP

    rows = ranking[:RANK_ROWS]
    x, y, right = window(d, (px, MARGIN, px + PANEL, MARGIN + KOMENDA_H), "KOMENDA")
    top = rows[0][1] if rows else 1.0
    for i, (goal, p) in enumerate(rows):
        won = i == 0
        d.text((x, y), "▶" if won else " ", font=FB, fill=GOLD)
        d.text((x + 20, y), goal, font=FB if won else F, fill=INK if won else DIM)
        d.text((right, y), f"{p:.2f}", font=FS, fill=GOLD if won else DIM, anchor="ra")
        d.rectangle([x + 20, y + 18, right, y + 24], fill="#141b24")
        d.rectangle([x + 20, y + 18, x + 20 + max(3, (right - x - 20) * p / max(top, 1e-6)), y + 24],
                    fill=GREEN if won else BLUE)
        y += RANK_H

    py = MARGIN + KOMENDA_H + GAP
    x, y, right = window(d, (px, py, px + PANEL, py + PAD_H), "PAD")
    cell = 30
    for name, (cx, cy, glyph) in DPAD.items():
        bx, by = x + cx * (cell + 5), y + cy * (cell + 5)
        on = name in pressed
        d.rectangle([bx, by, bx + cell, by + cell], fill=GOLD if on else GROUND,
                    outline=GOLD if on else DIM, width=2)
        d.text((bx + cell / 2, by + cell / 2), glyph, font=FB, anchor="mm", fill=GROUND if on else DIM)
    fx, fy = x + 3 * (cell + 5) + 16, y + cell + 5
    for name, bw in FACE:
        on = name in pressed
        d.rectangle([fx, fy, fx + bw, fy + cell], fill=GOLD if on else GROUND,
                    outline=GOLD if on else DIM, width=2)
        d.text((fx + bw / 2, fy + cell / 2), name, font=FS, anchor="mm", fill=GROUND if on else DIM)
        fx += bw + 8
        if fx + 66 > right:
            fx, fy = x + 3 * (cell + 5) + 16, fy + cell + 8

    sy = py + PAD_H + GAP
    stats = [("tura", decision.get("turn")), ("faza", decision.get("phase"))]
    stats += render_state(state_lines, ram)
    stats.append(("decyzja", f"{ex.get('ms', 0)} ms"))
    x, y, right = window(d, (px, sy, px + PANEL, sy + STAN_H), "STAN")
    for label, value in stats[:STAT_ROWS]:
        shown = str(value)
        # A profile writes its labels for the model, which has no panel to fit them in —
        # Final Fantasy's menu cursor explains itself in eleven words.
        room = right - x - (d.textlength(shown, font=F) if shown else 0) - 12
        d.text((x, y), ellipsize(d, str(label), room), font=F, fill=DIM if shown else INK)
        if shown:
            d.text((right, y), shown, font=F, fill=GOLD, anchor="ra")
        y += STAT_H
    d.text((x, sy + STAN_H - 24), "SemIf · Qwen3.5-4B · lokalnie, z pikseli", font=FS, fill=DIM)
    return img


def frames_of(run, first, last, caption):
    run = os.path.realpath(os.path.expanduser(run))
    profile = "ff1"
    campaign = os.path.join(run, "campaign.json")
    # The run does not record its profile, so take it from what the RAM digest contains.
    sample = sorted(glob.glob(os.path.join(run, "decisions", "turn-*.json")))
    if sample:
        ram = (json.load(open(sample[0])).get("ram") or {})
        if "gameState" in ram:
            profile = "smb"
    lines = profile_state(profile)

    out = []
    for path in sample:
        turn = int(os.path.basename(path)[5:10])
        if turn < first or turn > last:
            continue
        shot = os.path.join(run, "snapshots", f"turn-{turn:05d}.png")
        if not os.path.exists(shot):
            continue
        try:
            decision = json.load(open(path))
        except Exception:
            continue
        out.append(compose(shot, decision, ranking_for(run, turn), lines, caption))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+", help="run directories, played in the order given")
    ap.add_argument("--out", required=True, help="output .mp4 or .gif")
    ap.add_argument("--from", dest="first", type=int, default=1)
    ap.add_argument("--to", dest="last", type=int, default=10 ** 9)
    ap.add_argument("--fps", type=int, default=9)
    ap.add_argument("--caption", nargs="*", default=[], help="one caption per run, drawn under the screen")
    args = ap.parse_args()

    frames = []
    for i, run in enumerate(args.runs):
        caption = args.caption[i] if i < len(args.caption) else ""
        part = frames_of(run, args.first, args.last, caption)
        print(f"{run}: {len(part)} frames")
        frames += part
    if not frames:
        print("no frames — is that a finished run directory?")
        return 1

    if args.out.endswith(".gif"):
        frames[0].save(args.out, save_all=True, append_images=frames[1:],
                       duration=int(1000 / args.fps), loop=0, optimize=True)
    else:
        if not shutil.which("ffmpeg"):
            print("ffmpeg is not on the path; write a .gif instead")
            return 2
        tmp = tempfile.mkdtemp(prefix="knes-frames")
        try:
            for i, frame in enumerate(frames):
                frame.save(os.path.join(tmp, f"{i:06d}.png"))
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(args.fps),
                 "-i", os.path.join(tmp, "%06d.png"),
                 "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", args.out],
                check=True)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    print(f"wrote {args.out} — {len(frames)} frames, {len(frames) / args.fps:.0f}s at {args.fps} fps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
