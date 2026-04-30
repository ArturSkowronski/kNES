# kNES Testing Strategy

## Goals

- **Refactoring safety net** — confidence to restructure and improve code without breaking behavior
- **Regression prevention** — catch breakages when adding features (e.g., gamepad support, new mappers)

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Framework | Kotest 5.x (FunSpec + datatest) | Kotlin-native, data-driven tests ideal for instruction matrices |
| Mocking | None — real lightweight instances | Components are simple enough; avoids mock drift |
| CPU testing approach | Hand-written unit tests first, ROM integration second | LLM-generated unit tests are cheap; precise assertions; ROM test as capstone |
| Module priority | knes-emulator (must), knes-controllers (nice-to-have), UI modules (skip) | Logic lives in emulator; controllers are active development area |

## Test Infrastructure

### Dependencies

Added to `knes-emulator/build.gradle` and `knes-controllers/build.gradle`:

- `io.kotest:kotest-runner-junit5:5.x` — test runner
- `io.kotest:kotest-assertions-core:5.x` — assertions
- `io.kotest:kotest-framework-datatest:5.x` — data-driven table tests

### Build Configuration

Each module's `build.gradle` needs:

```kotlin
test {
    useJUnitPlatform()
}
```

### Test Source Layout

```
knes-emulator/src/test/kotlin/knes/emulator/
├── cpu/                        # CPU instruction & addressing mode tests
│   ├── CpuTestHarness.kt      # Setup helper: real CPU + Memory, load program, execute, inspect
│   ├── ArithmeticTest.kt       # ADC, SBC
│   ├── LogicalTest.kt          # AND, ORA, EOR
│   ├── ShiftRotateTest.kt      # ASL, LSR, ROL, ROR
│   ├── BranchTest.kt           # BCC, BCS, BEQ, BNE, BPL, BMI, BVC, BVS
│   ├── CompareTest.kt          # CMP, CPX, CPY
│   ├── IncDecTest.kt           # INC, DEC, INX, DEX, INY, DEY
│   ├── LoadStoreTest.kt        # LDA, LDX, LDY, STA, STX, STY
│   ├── StackTest.kt            # PHA, PHP, PLA, PLP
│   ├── TransferTest.kt         # TAX, TAY, TXA, TYA, TSX, TXS
│   └── ControlFlowTest.kt     # JMP, JSR, RTS, RTI, BRK, NOP
├── ppu/
│   ├── TileFetchingTest.kt
│   ├── SpriteEvaluationTest.kt
│   ├── PaletteLookupTest.kt
│   ├── VramAddressingTest.kt
│   ├── RegisterBehaviorTest.kt
│   └── ScanlineTimingTest.kt
├── papu/
│   ├── ChannelSquareTest.kt
│   ├── ChannelTriangleTest.kt
│   ├── ChannelNoiseTest.kt
│   └── ChannelDmTest.kt
├── mappers/
│   └── MapperDefaultTest.kt
├── MemoryTest.kt
└── NESIntegrationTest.kt

knes-controllers/src/test/kotlin/knes/controllers/
├── GamepadControllerTest.kt
├── KeyboardControllerTest.kt
└── input/
    └── Fm2InputLogParserTest.kt   # Moved from bin/ to proper location
```

## Priority 1: CPU Instruction Tests (~200-300 tests)

### Categories

| Category | Instructions | Key Edge Cases |
|----------|-------------|----------------|
| Arithmetic | ADC, SBC | Carry, overflow, signed/unsigned, decimal mode |
| Logical | AND, ORA, EOR | Zero flag, sign flag |
| Shift/Rotate | ASL, LSR, ROL, ROR | Carry in/out, accumulator vs memory mode |
| Branch | BCC, BCS, BEQ, BNE, BPL, BMI, BVC, BVS | Taken/not-taken, page crossing |
| Compare | CMP, CPX, CPY | Equal, greater, less, zero, sign |
| Inc/Dec | INC, DEC, INX, DEX, INY, DEY | Wraparound (0→255, 255→0), zero flag |
| Load/Store | LDA, LDX, LDY, STA, STX, STY | Zero flag, sign flag, all addressing modes |
| Stack | PHA, PHP, PLA, PLP | Stack pointer wraparound, flag restoration |
| Transfer | TAX, TAY, TXA, TYA, TSX, TXS | Zero/sign flags (except TXS) |
| Control | JMP, JSR, RTS, RTI, BRK, NOP | Indirect JMP bug (page boundary), interrupt flags |

### Test Pattern

Data-driven tests using Kotest `withData`:

```kotlin
class ArithmeticTest : FunSpec({
    context("ADC") {
        withData(
            nameFn = { "A=0x${it.a.toString(16)} + 0x${it.value.toString(16)} + C=${it.carry}" },
            listOf(
                AdcCase(a = 0x10, value = 0x20, carry = false, expected = 0x30, expectC = false, expectV = false, expectZ = false),
                AdcCase(a = 0xFF, value = 0x01, carry = false, expected = 0x00, expectC = true, expectV = false, expectZ = true),
                // ... more cases covering carry, overflow, sign, zero
            )
        ) { case ->
            val harness = CpuTestHarness()
            harness.setA(case.a)
            harness.setCarry(case.carry)
            harness.loadAndExecute(listOf(0x69, case.value)) // ADC immediate
            harness.assertA(case.expected)
            harness.assertCarry(case.expectC)
            harness.assertOverflow(case.expectV)
            harness.assertZero(case.expectZ)
        }
    }
})
```

### CpuTestHarness

A thin convenience class (not an abstraction framework) that:

- Creates real `CPU` + `Memory` instances
- Loads a byte sequence at a given address as a program
- Sets the PC to program start
- Executes N instructions
- Provides assertion helpers for registers and flags

### Addressing Mode Coverage

For instructions supporting multiple modes (e.g., LDA: immediate, zero page, zero page X, absolute, absolute X, absolute Y, indirect X, indirect Y), each mode gets its own `context` block within the same test class. This ensures the addressing logic itself is tested, not just the instruction logic.

## Priority 2: PPU Logic Tests (~50-80 tests)

Focus on calculational logic, not pixel output.

| Area | What's Tested | Why |
|------|--------------|-----|
| Tile fetching | Pattern table index calculation, name table address resolution | Core rendering math |
| Sprite evaluation | OAM scan logic, sprite-per-scanline limit, priority | Sprite 0 hit, overflow edge cases |
| Palette lookup | Attribute table → palette index → color mapping | Lookup tables, mirroring |
| VRAM addressing | Mirroring modes (horizontal, vertical, single-screen, four-screen) | Mapper-dependent behavior |
| Register behavior | PPUCTRL, PPUMASK, PPUSTATUS writes/reads, scroll latch | Double-write latch ($2005/$2006) is a classic bug source |
| Scanline timing | VBlank flag set/clear timing, sprite 0 hit timing | Cycle-sensitive, games depend on exact timing |

**Test pattern:** `FunSpec` with direct assertions. Set up PPU + Memory, write to registers, step through scanlines, assert internal state.

**NOT tested:** Actual pixel buffer output (UI-dependent), full frame rendering (covered by ROM integration test).

## Priority 3: PAPU Audio Channel Tests (~30-40 tests)

| Channel | What's Tested |
|---------|--------------|
| ChannelSquare (x2) | Duty cycle output, frequency timer, envelope decay, sweep unit |
| ChannelTriangle | Linear counter, step sequencer output, length counter |
| ChannelNoise | LFSR shift register, mode bit behavior, length counter |
| ChannelDM | Sample buffer, DMA address calculation |

**NOT tested:** Audio output (SourceDataLine), mixer — hardware-dependent.

## Priority 4: Mappers & Controllers (~30-40 tests)

### Mappers (~15-20 tests)

- `MapperDefault`: Bank switching, PRG/CHR mapping, mirroring configuration
- ROM data loading and address translation
- Battery RAM save/load roundtrip
- Each mapper type gets its own test class as more mappers are added

### Controllers (~15-20 tests)

- Move existing FM2 parser tests from `bin/` to proper `src/test/` location
- `GamepadController`: Button mapping, state read/write, multi-controller coordination
- `KeyboardController`: Key-to-button mapping
- Logic layer only — no hardware interaction

## Priority 5: Memory Tests (~10-15 tests)

- Read/write at boundaries (0x0000, 0xFFFF)
- Array operations (fill, copy)
- State save/load roundtrip

## Priority 6: ROM Integration Test (Capstone)

A single integration test using `nestest.nes` — community-standard CPU test ROM.

### How it works

1. Load `nestest.nes` ROM into the emulator
2. Set PC to `0xC000` (automated test entry point — no PPU required)
3. Run until halt condition (specific address or instruction count)
4. Read result code from memory `$0002` and `$0003` — `0x00` means all pass
5. Optionally: compare execution log against reference `nestest.log` line-by-line

### What this validates

- All CPU instructions working together
- Addressing modes, flag behavior, cycle counting
- Memory mapping basics
- Broad regression safety net — thousands of instruction sequences in one test

### Files

- ROM: `knes-emulator/src/test/resources/nestest.nes`
- Reference log: `knes-emulator/src/test/resources/nestest.log` (optional, for detailed comparison)

## Test Counts Summary

| Layer | Estimated Tests | Priority |
|-------|----------------|----------|
| CPU instructions | ~200-300 | 1 (highest) |
| PPU logic | ~50-80 | 2 |
| PAPU channels | ~30-40 | 3 |
| Mappers | ~15-20 | 4 |
| Controllers | ~15-20 | 4 |
| Memory | ~10-15 | 5 |
| ROM integration | ~1 (broad) | 6 (capstone) |
| **Total** | **~320-475** | |

## Runtime

- Unit tests: under 10 seconds
- ROM integration test: under 5 seconds
- Full suite: under 15 seconds
