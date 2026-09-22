#!/usr/bin/env bash
#
# The live demo: an agent playing, and a screen to watch it on.
#
#   tools/live_demo.sh smb     # Super Mario Bros
#   tools/live_demo.sh ff1     # Final Fantasy
#
# Keeps going. The agent is restarted whenever it exits — a run ends on its own when it
# reaches --max-turns, and it can also die on a bad frame or a dropped model — because a
# demo that stops between the introduction and the point is worse than no demo.
#
# Ctrl-C stops both the agent and the viewer.

set -uo pipefail
cd "$(dirname "$0")/.."

GAME="${1:-smb}"
case "$GAME" in
  smb) ROM="roms/smb.nes" ;;
  ff1) ROM="roms/ff.nes" ;;
  *)   echo "unknown game '$GAME' — expected smb or ff1"; exit 2 ;;
esac

if [ ! -f "$ROM" ]; then
  echo "no ROM at $ROM. Commercial ROMs stay out of the tree; put one there and run again."
  exit 2
fi

: "${KNES_DECISION:=pixels}"
: "${TURNS_PER_RUN:=50000}"
export KNES_DECISION

# The vision tower lives in SemIf's own environment; the text backend does not need it.
if [ "$KNES_DECISION" = "pixels" ] && [ -z "${SEMIF_VLM_PYTHON:-}" ]; then
  GUESS="$HOME/GitHub/SemIf/.venv-vlm/bin/python"
  if [ -x "$GUESS" ]; then
    export SEMIF_VLM_PYTHON="$GUESS"
    export SEMIF_SRC="${SEMIF_SRC:-$HOME/GitHub/SemIf/src}"
  else
    echo "KNES_DECISION=pixels needs SEMIF_VLM_PYTHON — an interpreter with mlx-vlm."
    echo "Set it, or run with KNES_DECISION=order for the no-model fallback."
    exit 2
  fi
fi

VIEWER_PID=""
if curl -fsS -o /dev/null http://localhost:9876/live 2>/dev/null; then
  echo "viewer already up"
else
  python3 tools/v2_viewer.py >/tmp/knes-viewer.log 2>&1 &
  VIEWER_PID=$!
  sleep 2
fi

cleanup() {
  echo
  echo "stopping"
  [ -n "$VIEWER_PID" ] && kill "$VIEWER_PID" 2>/dev/null
  pkill -f "knes.agent.MainKt" 2>/dev/null
  exit 0
}
trap cleanup INT TERM

echo
echo "  watch it:  http://localhost:9876/live"
echo "  game:      $GAME ($ROM)"
echo "  decisions: $KNES_DECISION"
echo

ATTEMPT=0
while true; do
  ATTEMPT=$((ATTEMPT + 1))
  echo "--- run $ATTEMPT ---"
  ./gradlew --quiet :knes-agent:run \
    -PappArgs="--fresh --reactive --rom=$ROM --profile=$GAME --max-turns=$TURNS_PER_RUN" \
    2>&1 | sed 's/\x1b\[[0-9;]*m//g'
  # A tight restart loop on a ROM that cannot load would spin the CPU and say nothing.
  echo "--- run $ATTEMPT ended, restarting in 5s (Ctrl-C to stop) ---"
  sleep 5
done
