# V2.5.0 evidence — vision phase classifier działa; nowy blocker = Coneria town outdoor exit

Run 2026-05-03 ~07:25. Args: `--max-skill-invocations=40 --wall-clock-cap-seconds=720`.
Trace: `2026-05-03T07-25-04.407449Z/trace.jsonl` (14 events). Outcome: `OutOfBudget`.

## Headline — co się POPRAWIŁO

V2.3.1 RAM-based mis-classification (spawn = "Indoors" mimo że overworld) ZNIKNĄŁ.
Vision classifier (Haiku 4.5) zadziałał poprawnie:

- turn #0 → `TitleOrMenu` (intro screen) ✓
- turn #2 → `Indoors(mapId=0)` (Coneria Castle throne room — FAKTYCZNE miejsce
  spawna w FF1, nie błąd) ✓
- ExitInterior z castle → **PIERWSZY UDANY EXIT W CAŁEJ V2.x**, party at
  overworld (146, 158) ✓
- agent zaczął walkować north toward Garland ✓

To pokazuje że:
1. Vision LLM trafnie odróżnia title / interior / overworld z 256×240 PNG.
2. Cała ścieżka V2.4.x (`InteriorMap`, `findPathToExit`, `ExitInterior`,
   stairs A-tap, FRAMES_PER_TILE=48) — DZIAŁA gdy phase classifier się nie
   pomyli. Spawn nie był nigdy wcześniej escape'owany, bo nigdy się nie
   zaczął *jako* legalny Indoors.

## Headline — co nadal nie działa

Po wyjściu z castle agent doszedł na overworldzie do (145, 152) i wpadł w
**Coneria Town** (mapId=8, localX=5, localY=28). Stamtąd 8 turnów pętli
`exitInterior` z `BLOCKED` aż budget out.

### Bug 1: town outdoor map ≠ interior

`mapId=8` w FF1 to "town outdoor map" — view miasta z budynkami, sklepami,
NPCs, ale wyjście odbywa się przez **krawędź mapy** (south/west edge), nie
przez DOOR/STAIRS/WARP. ExitInterior 16×16 viewport BFS nie widzi krawędzi
gdy party jest deep w mapie (localY=28; edge ~32; viewport sięga do
localY≈20). To dokładnie problem **V2.4.6-A** który odsunęliśmy w
"recommendation B" — full-map 64×64 BFS w `InteriorPathfinder`.

### Bug 2: cost-weighting (V2.4.6) ominięte

Path B (TOWN/CASTLE = cost 50 jako transit, 1 jako goal) nie zatrzymało
wejścia w Coneria. Hipoteza: LLM wybrał `walkOverworldTo(targetX, targetY)`
gdzie targetY trafił dokładnie w tile TOWN, więc destination-cost=1. Trzeba
zweryfikować w trace executor toolu (jakie targety dostawał walkOverworldTo).

### Bug 3: heldButtons stuck (turn #12 obs)

Executor wspomina `heldButtons show LEFT` w środku pętli ExitInterior. Po
nieudanym `walkOverworldTo` lub `step()` przycisk nie został wyzwolony.
Drobny ale potencjalnie powoduje "ruchy w złą stronę" w kolejnym kroku.
Sprawdzić `SessionActionController.executeSteps` — `releaseAll()` po path
shared/non-shared.

## V2.5.0 walidacja

Działa per design:
- `AnthropicVisionPhaseClassifier` → /v1/messages, image+JSON, frame-cached.
- `RamObserver.observeWithVision()` zwraca poprawne phase'y per turn.
- Battle/PostBattle/PartyDefeated nadal RAM (zero-cost path).

Cost: ~14 advisor/executor turnów × ~1 vision call = ~14 vision calls × ~$0.001
= ~$0.014 vision overhead. Pomijalne.

## Następny krok — V2.5.1 lub V2.4.6-A

**Rekomendacja**: ship full-map BFS dla `InteriorPathfinder` (oryginalny
V2.4.6-A z STATE doc). Mała zmiana, atakuje teraz konkretny live blocker
(town outdoor exit). Po tym agent powinien wyjść z Coneria Town i kontynuować
na północ.

Drugi krok (równolegle): zerknąć w trace na faktyczne `walkOverworldTo`
target coords z turnu #5 — czy LLM celował w TOWN tile, czy cost-weighting
ma bug.

## Files

- `knes-agent/.../perception/VisionPhaseClassifier.kt` — nowy
- `knes-agent/.../perception/RamObserver.kt` — `observeWithVision()` suspend
- `knes-agent/.../runtime/AgentSession.kt` — switched
- `knes-agent/.../Main.kt` — wires vision classifier

Commit: `7c57a79 feat(agent): V2.5.0 — vision-based phase classifier`.
