# FF1 Weapon Inventory RAM Addresses

**Date:** 2026-05-06
**Source:** Disch FF1 disassembly (same source as existing `ff1.json` entries).

## Layout

Per-character weapon inventory: 4 slots × 4 chars, stored at offset `+0x18` from each character's base address.

| Char | Base | Weapon slots |
|---|---|---|
| 1 | 0x6100 | 0x6118-0x611B |
| 2 | 0x6140 | 0x6158-0x615B |
| 3 | 0x6180 | 0x6198-0x619B |
| 4 | 0x61C0 | 0x61D8-0x61DB |

Stride confirmed by existing `ff1.json` stat entries:
- char1_str=0x6110, char2_str=0x6150 (+0x40)
- char3_str=0x6190, char4_str=0x61D0 (+0x40)

## Byte encoding

Each weapon-slot byte:
- Bit 7 (`0x80`): equipped flag (1 = equipped, 0 = in inventory but not equipped).
- Bits 0-6 (`0x7F`): weapon ID. ID 0 = empty slot.

Examples:
- `0x00`: empty
- `0x10`: weapon ID 0x10, not equipped
- `0x90`: weapon ID 0x10, equipped
- `0x80`: weapon ID 0 + equipped flag set (anomalous; should not appear)

## Field menu navigation

Standard FF1 NES bindings:
- `B` button on overworld → open field menu.
- Menu items in order: ITEM, MAGIC, EQUIP, STATUS, EXIT (EQUIP is index 2).
- Inside EQUIP: cursor to character (1-4 listed top to bottom), `A` selects.
- Character submenu: WEAPON tab is default. Cursor selects slot, `A` toggles equipped state.

## Verification path

These addresses are not confirmed by manual play (project principle: agent plays autonomously, dev does not). If e2e test (Task 10) reveals incorrect behavior, fall back to:
1. Cross-reference Disch disasm source files in repo (if vendored).
2. Inspect emulator RAM viewer at runtime (read-only, not gameplay) using `./gradlew :knes-debug:run` to confirm specific bytes change after the agent's own purchase action.
3. Update `ff1.json` and notes file accordingly.
