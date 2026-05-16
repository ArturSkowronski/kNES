# Remote mode — agent gra w Compose UI

Tryb `--remote` sprawia, że agent nie boot-uje własnego in-process
NES-a, tylko gada REST-em z emulatorem hostowanym przez Compose UI (Embedded
API na `:6502`). Audience widzi grę na żywo w Compose, a agent decyduje
zdalnie. Stary tryb (in-process) zostaje nietknięty — bez `--remote` wszystko
działa po staremu.

---

## Setup w 3 oknach terminala

### Okno 1 — Compose UI (emulator widoczny dla audience)
```bash
./gradlew :knes-compose-ui:run
```
W oknie kNES:
1. Klik **"Open ROM"** → wybierz `roms/ff.nes`
2. Klik **"Start API Server"** → REST na `http://localhost:6502`
3. Verify w innym oknie:
   ```bash
   curl http://localhost:6502/health
   # → {"status":"ok","romLoaded":true,"frames":N}
   ```

### Okno 2 — agent przez REST
```bash
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=400 --remote"
```

`--remote` bez wartości = `http://localhost:6502` (domyślnie).
Custom URL:
```bash
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=400 --remote=http://10.0.0.5:6502"
```

### Okno 3 — viewer (agent state + QR + Slido)
```bash
python3 tools/v2_viewer.py
# → http://localhost:9876/
```

---

## Co audience widzi gdzie

| Okno | Treść |
|---|---|
| Compose UI (na rzutniku) | Żywy emulator FF1, agent klika przyciski w czasie rzeczywistym |
| Viewer (drugi monitor / picture-in-picture) | Plan agenta, milestones, RAM, ostatnie decyzje + Slido pytania + QR feedback |
| Terminal (twoje śledzenie) | Log agenta: `[v2.main]`, `[v2.advisor]`, `[v2.executor]`, `GOLD BLEED` itd. |

---

## Co działa, czego NIE w trybie `--remote`

| Funkcja | Status |
|---|---|
| Wszystkie tools agenta (`step`, `tap`, `sequence`, `getState`, `getScreen`, `applyProfile`) | ✅ przez REST |
| `--cart` Cartographer | ✅ (te same tools) |
| Snapshot dumper (PNG do `~/.knes/runs/.../snapshots/`) | ✅ (ściąga z `/screen/base64`) |
| **`loadRom`** | ⛔ skipped — załaduj w UI ręcznie |
| **`--resume`** | ⛔ explicit error przy starcie (brak save/load przez REST) |
| **100-turn checkpointy** | ⛔ skipped (in-process `session` nie napędza emulatora) |
| Tryb in-process (BEZ `--remote`) | ✅ identyczny jak przed zmianami |

---

## Pre-flight checklist (5 min przed talkiem)

```bash
# 1. UI startuje
./gradlew :knes-compose-ui:run
# → Open ROM, Start API

# 2. API odpowiada
curl http://localhost:6502/health
# Powinno: {"status":"ok","romLoaded":true,"frames":N}

# 3. Profile ff1 aplikuje się
curl -X POST http://localhost:6502/profiles/ff1/apply
# Powinno: {"status":"ok",...}

# 4. State zwraca RAM ff1
curl http://localhost:6502/state | jq .ram
# Powinno: {"char1_class":0, "smPlayerX":..., ...}

# 5. Screenshot działa
curl http://localhost:6502/screen > /tmp/test.png && file /tmp/test.png
# Powinno: /tmp/test.png: PNG image data, 256 x 240

# 6. Agent startuje (smoke 20-tur, NIE 400)
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=20 --remote"
# Powinno: pierwsza linia
#   [main] REMOTE mode — using RemoteEmulatorToolset against http://localhost:6502
# a po niej
#   [RemoteEmulatorToolset] connected to http://localhost:6502 — health: ...
```

---

## Plan B (jeśli remote padnie podczas talku)

```bash
# Zabij agenta (Ctrl-C w oknie 2), zostaw Compose UI i viewer.
# Restart bez --remote — agent wraca do in-process trybu, ale audience
# nie widzi gry w Compose UI (in-process NES jest niewidoczny).
./gradlew :knes-agent:run -PappArgs="--fresh --max-turns=400"
# Audience widzi tylko viewer + snapshoty agenta — to też działa,
# słabszy demo wizualnie ale agent gra.
```

---

## Env (te same dla obu trybów)

```bash
export ANTHROPIC_API_KEY=...    # Haiku (Reviewer) + Anthropic SDK
export GEMINI_API_KEY=...       # Advisor + Executor + Cartographer

# Opcjonalne overridey modeli:
# export GEMINI_MODEL=gemini-3.1-pro-preview          # default
# export GEMINI_EXECUTOR_MODEL=gemini-3.1-flash-lite  # szybciej, gorzej widzi
```

---

## Co jest pod maską

- **`--remote=<url>` flag** w `knes-agent/src/main/kotlin/knes/agent/Config.kt`
  → null = stary in-process tryb, set = `RemoteEmulatorToolset`.
- **`RemoteEmulatorToolset`** (`knes-agent-tools/src/main/kotlin/knes/agent/tools/RemoteEmulatorToolset.kt`) —
  extends `EmulatorToolset`, override każdej tool-metody na
  `java.net.http.HttpClient` calls do `/step`, `/tap`, `/state`,
  `/screen/base64`, `/profiles/*`, `/press`, `/release`.
- **`Main.kt`**: 3-liniowy
  `if (cfg.remoteUrl != null) RemoteEmulatorToolset(...) else EmulatorToolset(...)`
  + skip checkpoint + skip resume w remote.
- **Zero zmian** w pętli campaign, milestones, Advisor, Executor, viewer —
  wszystko widzi `EmulatorToolset` (interfejs ten sam).
