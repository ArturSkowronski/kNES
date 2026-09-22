#!/usr/bin/env python3
"""
Live viewer for the v2 agent run.

Serves http://localhost:9876/ — auto-refreshes every 3s. Reads from
~/.knes/runs/latest/ and renders:
  - what each agent does (static narrative)
  - campaign goal / milestone progress
  - last executor decision + plan
  - current screenshot
  - last advisor + executor prompts (drill-down)

Run:
  python3 tools/v2_viewer.py
"""
import http.server
import socketserver
import json
import os
import base64
import html
import urllib.parse
from pathlib import Path

PORT = 9876
RUN_ROOT = Path.home() / ".knes" / "runs"


def run_link() -> Path:
    """The run to show: `latest`, which every fresh run repoints, then the older v2 link."""
    for name in ("latest", "latest-v2"):
        link = RUN_ROOT / name
        if link.exists():
            return link
    return RUN_ROOT / "latest"

AGENT_NARRATIVE = [
    ("cartographer", "Cartographer", "gemini-3-pro (vision)",
     "Phase 0 ONLY, OPT-IN via --cart. Sweeps overworld with vision calls within budget. "
     "Populates LandmarkMemory. Default OFF."),
    ("advisor", "Advisor", "gemini-3-pro (text+vision)",
     "~2-5% of LLM calls. Authors numbered plan into current_plan.json. Triggered on T0, "
     "stuck-signal, milestone advance. Sees: current milestone, campaign state, live RAM "
     "(phase, mapId, sm/world coords, gold), landmarks digest, screenshot."),
    ("executor", "Executor", "gemini-3-pro (text+vision)",
     "EVERY turn. Reads screenshot + RAM + recent moves + current goal. Emits either "
     "{sequence:[\"Up\"]} (raw button taps, max 2) OR {tool:\"buyAtShop\"|\"equipWeapon\"|"
     "\"restAtInn\"|\"useMenu\"}."),
    ("reviewer", "Reviewer", "deterministic (no LLM)",
     "Two passes: (1) every 10 turns runs verifyMilestones — re-evaluates each "
     "'done' milestone predicate against live RAM; reverts to in_progress on "
     "regression + triggers Advisor replan. (2) Every 50 turns runs audit() — "
     "currently placeholder, returns empty actions. Highlights in UI when active."),
    ("haiku", "Haiku (tool internal)", "claude-haiku-4-5",
     "Used inside ToolSurface only — directionTo(target) for townWalkVision per-step "
     "cardinal picking, scanCandidates() for approachSprite. NOT used by Executor any more."),
]


def read_json(p: Path):
    try:
        return json.loads(p.read_text())
    except Exception:
        return None


def latest_turn(run: Path) -> int:
    decisions = run / "decisions"
    if not decisions.exists():
        return 0
    files = sorted(decisions.glob("turn-*.json"))
    if not files:
        return 0
    return int(files[-1].stem.split("-")[1])


def list_recent_turns(run: Path, n=20):
    decisions = run / "decisions"
    if not decisions.exists():
        return []
    files = sorted(decisions.glob("turn-*.json"))[-n:]
    out = []
    for f in files:
        d = read_json(f) or {}
        ex = d.get("executor") or {}
        out.append({
            "turn": d.get("turn", 0),
            "phase": d.get("phase", "?"),
            "tool": ex.get("tool", "?"),
            "args": ex.get("args", {}),
            "outcome": ex.get("outcome", "?"),
            "message": ex.get("message", "") or "",
            "reasoning": ex.get("reasoningSummary", "") or "",
            "ms": ex.get("ms", 0),
            "ram": d.get("ram", {}),
        })
    return out


def b64_png(p: Path) -> str:
    if not p.exists():
        return ""
    return base64.b64encode(p.read_bytes()).decode("ascii")


def _h(s):
    return html.escape(str(s)) if s is not None else ""


def read_goal_decision(run: Path):
    """The newest `executor-goals` dump, split into its three sections.

    Written by ExecutorAgent when KNES_DECISION is on: the state the model read, every
    option it was shown, and the full ranking. Returns None when the run decided its turns
    with the chat model instead, so the panel simply does not appear.
    """
    prompts = run / "prompts"
    if not prompts.exists():
        return None
    dumps = sorted(prompts.glob("T*-executor-goals.txt"))
    if not dumps:
        return None
    newest = dumps[-1]
    text = newest.read_text()
    sections = {}
    current = None
    for line in text.splitlines():
        if line.startswith("=== ") and line.endswith(" ==="):
            current = line.strip("= ").strip().lower()
            sections[current] = []
        elif current:
            sections[current].append(line)

    options = []
    for line in sections.get("options", []):
        if line.startswith("- ") and ": " in line:
            goal_id, description = line[2:].split(": ", 1)
            options.append((goal_id, description))

    ranking = []
    for line in sections.get("ranking", []):
        parts = line.rsplit(" ", 1)
        if len(parts) == 2:
            try:
                ranking.append((parts[0], float(parts[1])))
            except ValueError:
                pass

    return {
        "turn": int(newest.stem[1:6]),
        "state": "\n".join(sections.get("state", [])).strip(),
        "options": dict(options),
        "ranking": ranking,
    }


def render_goal_decision(decision) -> str:
    """The typed decision, as bars.

    The point the panel has to make is that the answer could not have been anything else:
    every bar is an option that was written down before the model was asked.
    """
    if not decision:
        return (
            '<div style="background:#161616;padding:12px;border-radius:6px;color:#888;font-size:12px">'
            'This run decided its turns with the chat model. Set '
            '<code style="color:#7cb">KNES_DECISION=semif</code> (or <code style="color:#7cb">order</code>) '
            'to put the goal selector in charge — see docs/typed-decisions.md.</div>'
        )
    ranking = decision["ranking"]
    if not ranking:
        return '<div style="color:#888">(only one goal applied — nothing to decide)</div>'
    top = ranking[0][1] or 1.0
    rows = []
    for i, (goal_id, probability) in enumerate(ranking):
        width = max(1.0, probability / top * 100.0)
        won = i == 0
        colour = "#4caf50" if won else "#33506b"
        rows.append(
            f'<div style="margin-bottom:6px">'
            f'<div style="display:flex;justify-content:space-between;font-size:12px;'
            f'color:{"#dfe" if won else "#9ab"};font-family:monospace">'
            f'<span>{"&#9654; " if won else "&nbsp;&nbsp;&nbsp;"}{_h(goal_id)}</span>'
            f'<span>{probability:.4f}</span></div>'
            f'<div style="background:#12181f;border-radius:3px;height:8px;overflow:hidden">'
            f'<div style="background:{colour};width:{width:.1f}%;height:100%"></div></div>'
            f'<div style="font-size:11px;color:#667;margin-top:2px">'
            f'{_h(decision["options"].get(goal_id, ""))}</div>'
            f'</div>'
        )
    return (
        f'<div style="background:#161616;padding:12px;border-radius:6px">'
        f'<div style="font-size:11px;color:#888;margin-bottom:8px">'
        f'T{decision["turn"]:05d} · {len(ranking)} goals said they could run · '
        f'the model ranked them · nothing else was possible to answer</div>'
        f'{"".join(rows)}</div>'
    )



# ---------------------------------------------------------------- live demo page

BUTTONS = ("Up", "Down", "Left", "Right", "B", "A", "SELECT", "START")


def live_payload(run: Path) -> dict:
    """Everything the HUD redraws, small enough to poll several times a second.

    The screen comes from `live.png`, which the tool surface rewrites after every single
    button press — not from the per-turn dump, which would only move once a turn and make
    a 5 Hz agent look like a 1 Hz one.
    """
    folder = run / "decisions"
    files = sorted(folder.glob("turn-*.json")) if folder.exists() else []
    recent = [read_json(f) or {} for f in files[-6:]]
    decision = recent[-1] if recent else {}
    executor = decision.get("executor", {}) or {}
    pressed = [b.strip() for b in (executor.get("args", {}) or {}).get("buttons", "").split(",") if b.strip()]

    goals = read_goal_decision(run) or {}
    live_png = run / "snapshots" / "live.png"
    turn_png = run / "snapshots" / f"turn-{decision.get('turn', 0):05d}.png"
    frame = live_png if live_png.exists() else turn_png

    ram = decision.get("ram", {}) or {}
    campaign = read_json(run / "campaign.json") or {}
    milestones = [
        {"id": m.get("id", "?"), "status": m.get("status", "?")}
        for m in campaign.get("milestones", [])
    ]

    def gold(r):
        lo, hi = r.get("goldLow"), r.get("goldHigh")
        if lo is None:
            return r.get("gold")
        return lo + 256 * (hi or 0)

    def digits(r, prefix, count):
        """SMB keeps its score and timer as one decimal digit per address."""
        parts = [r.get(f"{prefix}{i}") for i in range(1, count + 1)]
        if any(p is None for p in parts):
            return None
        return "".join(str(p) for p in parts)

    # The status window shows what this game actually has. Final Fantasy counts gold and
    # milestones; Mario counts lives, coins and how far into the world he is. Naming both
    # in one fixed list would leave half of it reading "None" whichever game is running.
    rows = []
    if gold(ram) is not None:
        rows.append(["zloto", f"{gold(ram)} G"])
    if ram.get("lives") is not None:
        rows.append(["zycia", ram["lives"]])
    if ram.get("coins") is not None:
        rows.append(["monety", ram["coins"]])
    if ram.get("world") is not None and ram.get("level") is not None:
        rows.append(["swiat", f"{ram['world'] + 1}-{ram['level'] + 1}"])
    if digits(ram, "timer", 3) is not None:
        rows.append(["czas", digits(ram, "timer", 3)])
    if digits(ram, "score", 3) is not None:
        rows.append(["wynik", digits(ram, "score", 3) + "00"])
    if ram.get("enemyActive") is not None:
        rows.append(["wrogowie", ram["enemyActive"]])

    return {
        "turn": decision.get("turn", 0),
        "phase": decision.get("phase", "?"),
        "tool": executor.get("tool", ""),
        "args": executor.get("args", {}),
        "pressed": pressed,
        "outcome": executor.get("outcome", ""),
        "ms": executor.get("ms", 0),
        "model": executor.get("reasoningSummary", "").split(" via ")[-1],
        "ranking": goals.get("ranking", []),
        "options": goals.get("options", {}),
        "screen": b64_png(frame),
        "position": [ram.get("smPlayerX"), ram.get("smPlayerY")],
        "world": [ram.get("worldX"), ram.get("worldY")],
        "rows": rows,
        "milestones": milestones,
        "log": [
            {
                "turn": d.get("turn", 0),
                "text": ((d.get("executor", {}) or {}).get("message", "") or
                         (d.get("executor", {}) or {}).get("tool", ""))[:90],
                "outcome": (d.get("executor", {}) or {}).get("outcome", ""),
            }
            for d in reversed(recent)
        ],
    }


LIVE_PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>kNES — na żywo</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Press+Start+2P&family=VT323&display=swap">
<style>
  /* One visual world: a Final Fantasy screen. Each kind of information gets its own
     window, the way FF1 splits the party, the command menu and the message box. */
  :root {
    --ground:#000; --edge:#f8f8f8; --ink:#f8f8f8; --dim:#9c9c9c;
    --gold:#f8b800; --blue:#0058f8; --red:#f83800; --green:#58d854;
  }
  * { box-sizing: border-box; }
  body {
    margin:0; padding:18px; background:var(--ground); color:var(--ink);
    font-family:"VT323",ui-monospace,monospace; font-size:20px; line-height:1.35;
  }
  .hud { display:grid; gap:18px; grid-template-columns:minmax(0,1fr) 380px;
         align-items:start; max-width:1180px; margin:0 auto; }
  .stack { display:flex; flex-direction:column; gap:18px; min-width:0; }
  @media (max-width: 900px) { .hud { grid-template-columns:minmax(0,1fr); } }

  .win { border:4px solid var(--edge); background:var(--ground);
         box-shadow: inset 0 0 0 4px var(--ground), inset 0 0 0 6px var(--edge);
         padding:16px 16px 14px; min-width:0; }
  .win h2 { font-family:"Press Start 2P",monospace; font-size:10px; font-weight:400;
            letter-spacing:.06em; color:var(--gold); margin:0 0 12px; text-transform:uppercase; }

  #screen { display:block; width:100%; height:auto; image-rendering:pixelated; border:4px solid var(--edge); }
  .screenwin { padding:10px; }

  /* Command window: the cursor marks what won, exactly as FF1 marks a choice. */
  .cmd { display:flex; flex-direction:column; gap:7px; }
  .opt { display:grid; grid-template-columns:1.3em 1fr auto; gap:0 6px; align-items:baseline; color:var(--dim); }
  .opt.chosen { color:var(--ink); }
  .opt .cur { color:var(--gold); visibility:hidden; }
  .opt.chosen .cur { visibility:visible; }
  .opt .p { font-variant-numeric:tabular-nums; }
  .opt.chosen .p { color:var(--gold); }
  .meter { grid-column:2 / -1; height:6px; background:#202020; margin-top:2px; }
  .meter > i { display:block; height:100%; background:var(--blue); transition:width .1s linear; }
  .opt.chosen .meter > i { background:var(--green); }

  /* Status window: label left, value right, like HP/MP rows. */
  .rows { display:grid; grid-template-columns:1fr auto; gap:7px 14px; margin:0; }
  .rows dt { color:var(--dim); }
  .rows dd { margin:0; text-align:right; color:var(--gold); font-variant-numeric:tabular-nums; }

  /* Controller: NES pad, lit while a button is down. */
  .pad { display:grid; grid-template-columns:repeat(3,34px); gap:5px; }
  .face { display:flex; gap:8px; margin-top:10px; }
  .key { height:34px; border:3px solid var(--dim); color:var(--dim);
         display:flex; align-items:center; justify-content:center;
         font-family:"Press Start 2P",monospace; font-size:9px; }
  .face .key { width:48px; }
  .face .key.wide { width:76px; }
  .key.on { border-color:var(--gold); color:var(--ground); background:var(--gold); }

  .log { display:flex; flex-direction:column; gap:4px; }
  .log div { color:var(--dim); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .log div:first-child { color:var(--ink); }
  .log .t { color:var(--blue); }
  .log .bad { color:var(--red); }

  .ms { list-style:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:6px 14px; }
  .ms li { color:var(--dim); }
  .ms li.done { color:var(--green); }
  .ms li.now { color:var(--gold); }
  .ms li.done::before { content:"\2714 "; }
  .ms li.now::before { content:"\25B6 "; }

  .foot { color:var(--dim); font-size:18px; margin:6px 0 0; }
</style></head><body>
<div class="hud">
  <div class="stack">
    <div class="win screenwin"><img id="screen" alt="ekran NES"></div>
    <div class="win">
      <h2>Dziennik</h2>
      <div class="log" id="log"></div>
    </div>
  </div>

  <div class="stack">
    <div class="win">
      <h2>Komenda</h2>
      <div class="cmd" id="cmd"></div>
      <p class="foot" id="note"></p>
    </div>

    <div class="win">
      <h2>Pad</h2>
      <div class="pad">
        <span></span><div class="key" data-b="Up">&#9650;</div><span></span>
        <div class="key" data-b="Left">&#9664;</div><span></span><div class="key" data-b="Right">&#9654;</div>
        <span></span><div class="key" data-b="Down">&#9660;</div><span></span>
      </div>
      <div class="face">
        <div class="key wide" data-b="SELECT">SEL</div>
        <div class="key wide" data-b="START">START</div>
        <div class="key" data-b="B">B</div>
        <div class="key" data-b="A">A</div>
      </div>
    </div>

    <div class="win">
      <h2>Stan</h2>
      <dl class="rows" id="rows"></dl>
    </div>

    <div class="win" id="questwin">
      <h2>Wyprawa</h2>
      <ul class="ms" id="ms"></ul>
    </div>
  </div>
</div>
<script>
const KEYS = document.querySelectorAll('.key');
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const el = id => document.getElementById(id);

async function tick() {
  let d;
  try { d = await (await fetch('/api/live', {cache:'no-store'})).json(); }
  catch (e) { return; }   // the run may be mid-write; try again next tick

  if (d.screen) el('screen').src = 'data:image/png;base64,' + d.screen;

  const down = new Set(d.pressed || []);
  KEYS.forEach(k => k.classList.toggle('on', down.has(k.dataset.b)));

  const rank = d.ranking || [];
  const top = rank.length ? (rank[0][1] || 1) : 1;
  el('cmd').innerHTML = rank.map(([id, p], i) =>
    '<div class="opt' + (i === 0 ? ' chosen' : '') + '" title="' + esc((d.options||{})[id] || '') + '">' +
      '<span class="cur">&#9654;</span>' +
      '<span>' + esc(id) + '</span>' +
      '<span class="p">' + p.toFixed(2) + '</span>' +
      '<span class="meter"><i style="width:' + Math.max(2, p/top*100).toFixed(1) + '%"></i></span>' +
    '</div>').join('') || '<div class="opt"><span></span><span>czekam na decyzję…</span><span></span></div>';

  el('note').textContent = rank.length
    ? rank.length + ' celów mogło ruszyć. Jeden musiał wygrać.' : '';

  const world = (d.world || []).filter(v => v !== null);
  const LABEL = {zloto:'złoto', zycia:'życia', monety:'monety', swiat:'świat',
                 czas:'czas', wynik:'wynik', wrogowie:'wrogowie'};
  el('rows').innerHTML =
    row('tura', d.turn) +
    row('faza', d.phase) +
    row('pozycja', (d.position || []).join(',')) +
    (world.length ? row('mapa świata', world.join(',')) : '') +
    (d.rows || []).map(([k, v]) => row(LABEL[k] || k, v)).join('') +
    row('decyzja', d.ms + ' ms') +
    row('model', (d.model || '').replace('semif/', ''));

  const ms = d.milestones || [];
  el('questwin').hidden = ms.length === 0;   // Mario has no campaign to show
  el('ms').innerHTML = ms.map(m =>
    '<li class="' + (m.status === 'done' ? 'done' : m.status === 'in_progress' ? 'now' : '') + '">' +
    esc(m.id) + '</li>').join('');

  el('log').innerHTML = (d.log || []).map(l =>
    '<div><span class="t">T' + String(l.turn).padStart(4,'0') + '</span> ' +
    '<span class="' + (l.outcome && l.outcome !== 'ok' ? 'bad' : '') + '">' + esc(l.text) + '</span></div>'
  ).join('');
}
function row(k, v) { return '<dt>' + esc(k) + '</dt><dd>' + esc(v) + '</dd>'; }

tick();
setInterval(tick, 120);
</script>
</body></html>"""



def render_html(run: Path) -> str:
    if not run.exists():
        return f"<html><body><h1>No run dir at {run}</h1><p>Start a smoke first.</p></body></html>"

    campaign = read_json(run / "campaign.json") or {}
    plan = read_json(run / "current_plan.json") or {}
    last_t = latest_turn(run)
    recent = list_recent_turns(run, 25)
    # full RAM from latest decision
    last_decision = read_json(run / "decisions" / f"turn-{last_t:05d}.json") or {}
    ram = (last_decision.get("ram") or {})
    # Prefer live snapshot (overwritten per button tap during sequence) when
    # newer than the per-turn dump.
    live_png = run / "snapshots" / "live.png"
    turn_png = run / "snapshots" / f"turn-{last_t:05d}.png"
    chosen = live_png if (live_png.exists() and (
        not turn_png.exists() or live_png.stat().st_mtime > turn_png.stat().st_mtime
    )) else turn_png
    screenshot_b64 = b64_png(chosen)
    screen_label = chosen.name

    # Active agent
    active_agent = "idle"
    active_age_s = 0.0
    active_path = run / "active.json"
    if active_path.exists():
        try:
            data = json.loads(active_path.read_text())
            active_agent = data.get("agent", "idle")
            ts_ms = data.get("ts", 0)
            if ts_ms:
                active_age_s = max(0.0, (__import__("time").time() * 1000 - ts_ms) / 1000.0)
        except Exception:
            pass

    # latest prompts
    advisor_prompt = ""
    executor_prompt = ""
    cart_prompts = []  # list of (iter_num, txt, png_b64)
    prompts_dir = run / "prompts"
    if prompts_dir.exists():
        adv = sorted(prompts_dir.glob("T*-advisor.txt"))
        exe = sorted(prompts_dir.glob("T*-executor.txt"))
        carts = sorted(prompts_dir.glob("T00000-cart-*.txt"))
        if adv:
            advisor_prompt = adv[-1].read_text()
        if exe:
            executor_prompt = exe[-1].read_text()
        for cf in carts:
            n = int(cf.stem.split("cart-")[1])
            png = run / "snapshots" / f"cart-{n:05d}.png"
            cart_prompts.append((n, cf.read_text(), b64_png(png)))

    goal_decision = read_goal_decision(run)

    milestones = campaign.get("milestones", [])
    plans_history = campaign.get("plans", [])

    def chip(status):
        color = {"done": "#4caf50", "in_progress": "#2196f3", "pending": "#9e9e9e"}.get(status, "#666")
        return f'<span style="background:{color};color:white;padding:2px 8px;border-radius:8px;font-size:11px">{status}</span>'

    def phase_chip(phase):
        color = {
            "Town": "#8bc34a", "Overworld": "#ffc107", "Indoors": "#ff5722",
            "Battle": "#e91e63", "Boot": "#9c27b0",
        }.get(phase, "#666")
        return f'<span style="background:{color};color:white;padding:1px 6px;border-radius:6px;font-size:10px">{_h(phase)}</span>'

    def outcome_chip(o):
        color = {"ok": "#4caf50", "fail": "#f44336", "reject": "#ff9800"}.get(o, "#666")
        return f'<span style="background:{color};color:white;padding:1px 6px;border-radius:6px;font-size:10px">{_h(o)}</span>'

    def agent_block(slug, name, model, role):
        is_active = slug == active_agent
        bg = "#1f4a2c" if is_active else "#1e1e1e"
        border = "2px solid #4caf50" if is_active else "2px solid transparent"
        chip = (f' <span style="background:#4caf50;color:white;padding:1px 6px;'
                f'border-radius:8px;font-size:10px">ACTIVE · {active_age_s:.0f}s</span>') if is_active else ""
        return (
            f'<div style="background:{bg};border:{border};padding:10px;border-radius:6px;margin-bottom:6px">'
            f'<b>{_h(name)}</b>{chip} <span style="color:#888;font-size:11px">— {_h(model)}</span><br>'
            f'<span style="color:#ccc;font-size:12px">{_h(role)}</span>'
            f'</div>'
        )

    agents_html = "".join(agent_block(slug, name, model, role) for slug, name, model, role in AGENT_NARRATIVE)

    # RAM panel — compact tables grouped by purpose
    def ram_v(k, default="?"):
        v = ram.get(k)
        return _h(v) if v is not None else default
    gold_total = (ram.get("goldLow", 0) or 0) + 256 * (ram.get("goldMid", 0) or 0) + 65536 * (ram.get("goldHigh", 0) or 0)
    def w_byte(c, s):
        v = ram.get(f"char{c}_weapon{s}")
        if v is None or v == 0: return "·"
        eq = "*" if v >= 128 else " "
        return f"{v & 0x7F:>2}{eq}"
    weap_rows = "".join(
        f'<tr><td style="padding:1px 4px;color:#888">char{c}</td>'
        f'<td style="padding:1px 4px">{w_byte(c,0)}</td>'
        f'<td style="padding:1px 4px">{w_byte(c,1)}</td>'
        f'<td style="padding:1px 4px">{w_byte(c,2)}</td>'
        f'<td style="padding:1px 4px">{w_byte(c,3)}</td>'
        f'<td style="padding:1px 4px;color:#888">hp:{ram_v(f"char{c}_hpLow")}/{ram_v(f"char{c}_maxHpLow")}</td>'
        f'</tr>'
        for c in range(1, 5)
    )
    # Trim to run-relevant only: phase signals, coords, gold, weapons.
    weap_summary = " | ".join(
        f"c{c}:" + (",".join(
            (f"{(ram.get(f'char{c}_weapon{s}',0) or 0) & 0x7F}" +
             ("*" if (ram.get(f'char{c}_weapon{s}',0) or 0) >= 128 else ""))
            for s in range(4) if (ram.get(f'char{c}_weapon{s}',0) or 0) != 0
        ) or "—")
        for c in range(1, 5)
    )
    ram_html = (
        f'<div style="background:#161616;padding:10px;border-radius:6px;font-family:monospace;font-size:12px;line-height:1.6">'
        f'<div><span style="color:#7cb">phase:</span> mapflags=0x{(ram.get("mapflags",0) or 0):02X} '
        f'mapId={ram_v("currentMapId")} screenState=0x{(ram.get("screenState",0) or 0):02X}</div>'
        f'<div><span style="color:#7cb">coords:</span> sm=({ram_v("smPlayerX")},{ram_v("smPlayerY")}) '
        f'world=({ram_v("worldX")},{ram_v("worldY")})</div>'
        f'<div><span style="color:#7cb">gold:</span> {gold_total} G</div>'
        f'<div><span style="color:#7cb">weapons:</span> {weap_summary} <span style="color:#888">(* = equipped)</span></div>'
        f'</div>'
    )

    milestones_html = "".join(
        f'<div style="margin-bottom:4px"><b>{_h(m.get("id","?"))}</b> {chip(m.get("status","?"))}</div>'
        for m in milestones
    ) or "<i>(no milestones loaded)</i>"

    plan_steps_html = ""
    if plan:
        cursor = plan.get("cursor", 0)
        for s in plan.get("steps", []):
            idx = s.get("index", "?")
            mark = "▶" if idx == cursor else "  "
            tool = _h(s.get("intentTool", "?"))
            args = _h(json.dumps(s.get("intentArgs") or {}))
            desc = _h(s.get("description", ""))
            color = "#4caf50" if idx < cursor else ("#ffeb3b" if idx == cursor else "#666")
            plan_steps_html += (
                f'<div style="color:{color};margin-bottom:4px;font-family:monospace;font-size:12px">'
                f'{mark} [{idx}] {tool}({args}) — <span style="color:#999">{desc}</span></div>'
            )
    else:
        plan_steps_html = "<i>(no plan)</i>"

    plan_meta = ""
    if plan:
        plan_meta = (
            f'<div style="color:#888;font-size:11px;margin-bottom:6px">'
            f'milestone={_h(plan.get("milestone","?"))} '
            f'createdAtTurn={_h(plan.get("createdAtTurn","?"))} '
            f'cursor={_h(plan.get("cursor","?"))}/{_h(len(plan.get("steps",[])))}'
            f'</div>'
        )

    rows_html = ""
    for r in reversed(recent):
        ram = r["ram"]
        rows_html += (
            "<tr>"
            f'<td style="padding:4px 8px">T{r["turn"]}</td>'
            f'<td style="padding:4px 8px">{phase_chip(r["phase"])}</td>'
            f'<td style="padding:4px 8px;font-family:monospace;font-size:11px;color:#aaa">'
            f'sm=({_h(ram.get("smPlayerX","?"))},{_h(ram.get("smPlayerY","?"))}) '
            f'world=({_h(ram.get("worldX","?"))},{_h(ram.get("worldY","?"))}) '
            f'mid={_h(ram.get("currentMapId","?"))} mf={_h(ram.get("mapflags","?"))}'
            f'</td>'
            f'<td style="padding:4px 8px;font-family:monospace;font-size:11px">'
            f'<b>{_h(r["tool"])}</b>({_h(json.dumps(r["args"]))})'
            f'</td>'
            f'<td style="padding:4px 8px">{outcome_chip(r["outcome"])}</td>'
            f'<td style="padding:4px 8px;color:#888;font-size:10px;max-width:380px">{_h(r["message"][:200])}</td>'
            f'<td style="padding:4px 8px;color:#666;font-size:10px;max-width:200px">{_h(r["reasoning"][:120])}</td>'
            f'</tr>'
        )

    advisor_history_html = "".join(
        f'<div style="font-size:11px;margin-bottom:3px">'
        f'<span style="color:#666">T{_h(p.get("turn","?"))}</span> '
        f'<span style="color:#aaa">{_h(p.get("reason",""))}</span> → '
        f'<span style="color:#ccc">{_h(p.get("summary","")[:120])}</span>'
        f'</div>'
        for p in plans_history[-8:]
    )

    screen_html = (
        f'<img src="data:image/png;base64,{screenshot_b64}" '
        f'style="width:512px;height:480px;image-rendering:pixelated;border:2px solid #444"/>'
        f'<div style="color:#888;font-size:11px;margin-top:4px">file: {_h(screen_label)}</div>'
        if screenshot_b64
        else "<i>(no screenshot)</i>"
    )

    return f"""<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="3">
<title>kNES v2 Agent Live</title>
<style>
body{{background:#0d0d0d;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:14px}}
h2{{border-bottom:1px solid #333;padding-bottom:4px;margin:14px 0 8px;color:#7cb}}
table{{border-collapse:collapse;width:100%;font-size:12px}}
tr:nth-child(even){{background:#161616}}
tr:hover{{background:#222}}
pre{{background:#111;padding:10px;border-radius:6px;overflow:auto;max-height:400px;font-size:11px;color:#bbb}}
.col{{display:inline-block;vertical-align:top;margin-right:14px}}
details summary{{cursor:pointer;color:#7cb;padding:4px 0}}
</style></head><body>

<h1 style="margin:0">kNES v2 — agent live (T{last_t})</h1>
<div style="color:#888;font-size:12px;margin-bottom:8px">
  run: <code>{_h(str(run.resolve()))}</code> · auto-refresh 3s
</div>

<div class="col" style="width:540px">
  <h2>Current screen (T{last_t})</h2>
  {screen_html}
</div>

<div class="col" style="width:330px">
  <h2>Typed decision (goals &rarr; SemIf)</h2>
  {render_goal_decision(goal_decision)}
  <h2>RAM (T{last_t})</h2>
  {ram_html}
  <h2>Goal · milestones</h2>
  {milestones_html}
  <h2>Current plan</h2>
  {plan_meta}
  {plan_steps_html}
  <h2>Advisor plan history</h2>
  {advisor_history_html or "<i>(none)</i>"}
</div>

<div class="col" style="width:380px">
  <h2>Agents</h2>
  {agents_html}
</div>

<h2>Cartographer iters (Phase 0 — pre-campaign)</h2>
<div style="font-size:11px;color:#888;margin-bottom:6px">{len(cart_prompts)} vision calls; click to expand</div>
{"".join(
    f'<details style="margin-bottom:6px;background:#161616;padding:6px;border-radius:4px">'
    f'<summary><b>cart iter {n}</b> — click for image + prompt + response</summary>'
    f'<div style="display:flex;gap:14px;margin-top:6px">'
    f'<img src="data:image/png;base64,{png}" style="width:256px;height:240px;image-rendering:pixelated;border:1px solid #333"/>'
    f'<pre style="flex:1;max-height:360px;margin:0">{_h(txt)}</pre>'
    f'</div></details>'
    for n, txt, png in cart_prompts
) or "<i>(no cartographer prompts dumped yet)</i>"}

<h2>Last 25 turns (newest first)</h2>
<table>
<thead><tr style="text-align:left;color:#7cb">
<th style="padding:4px 8px">turn</th>
<th style="padding:4px 8px">phase</th>
<th style="padding:4px 8px">RAM</th>
<th style="padding:4px 8px">tool(args)</th>
<th style="padding:4px 8px">outcome</th>
<th style="padding:4px 8px">message</th>
<th style="padding:4px 8px">reasoning</th>
</tr></thead>
<tbody>{rows_html}</tbody>
</table>

<h2>State the decision model read</h2>
<details><summary>show</summary><pre>{_h(goal_decision["state"]) if goal_decision else "(no typed decisions in this run)"}</pre></details>

<h2>Latest Advisor prompt + response</h2>
<details><summary>show</summary><pre>{_h(advisor_prompt) or "(no advisor prompts dumped yet)"}</pre></details>

<h2>Latest Executor (Sonnet) prompt + response</h2>
<details><summary>show</summary><pre>{_h(executor_prompt) or "(no executor prompts dumped yet)"}</pre></details>

</body></html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        link = run_link()
        run_now = link.resolve() if link.exists() else link
        if self.path.startswith("/api/live"):
            body = json.dumps(live_payload(run_now)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/live"):
            body = LIVE_PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        path = urllib.parse.urlparse(self.path).path
        link = run_link()
        run = link.resolve() if link.exists() else link
        if path == "/" or path == "/index.html":
            html_text = render_html(run)
            data = html_text.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):  # quiet
        pass


class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True


def main():
    if not run_link().exists():
        print(f"[v2-viewer] WARN: {run_link()} does not exist yet — start a smoke first.")
    print(f"[v2-viewer] http://localhost:{PORT}/  (Ctrl-C to stop)")
    with ReusableTCPServer(("127.0.0.1", PORT), Handler) as srv:
        srv.serve_forever()


if __name__ == "__main__":
    main()
