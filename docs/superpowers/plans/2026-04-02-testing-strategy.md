# kNES Testing Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add comprehensive test coverage to the kNES emulator using Kotest, prioritized by risk (CPU > PPU > PAPU > Memory/Mappers > Controllers), with a ROM integration capstone.

**Architecture:** Tests use real component instances (no mocks). A `CpuTestHarness` wraps CPU setup/step/assert. CPU gets a minimal `step()` method and `internal` flag visibility to enable testing. Kotest data-driven tests (`withData`) cover instruction x addressing-mode matrices.

**Tech Stack:** Kotest 5.9.1 (runner-junit5, assertions-core, framework-datatest), Kotlin, Gradle, JUnit Platform

---

## File Map

### New Files

| File | Purpose |
|------|---------|
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/CpuTestHarness.kt` | Harness: create CPU + Memory, load program, step, assert |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/TestMemoryAccess.kt` | Simple `MemoryAccess` impl wrapping Memory for tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/ArithmeticTest.kt` | ADC, SBC tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/LogicalTest.kt` | AND, ORA, EOR tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/ShiftRotateTest.kt` | ASL, LSR, ROL, ROR tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/BranchTest.kt` | BCC, BCS, BEQ, BNE, BPL, BMI, BVC, BVS tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/CompareTest.kt` | CMP, CPX, CPY tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/IncDecTest.kt` | INC, DEC, INX, DEX, INY, DEY tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/LoadStoreTest.kt` | LDA, LDX, LDY, STA, STX, STY tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/StackTest.kt` | PHA, PHP, PLA, PLP tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/TransferTest.kt` | TAX, TAY, TXA, TYA, TSX, TXS tests |
| `knes-emulator/src/test/kotlin/knes/emulator/cpu/ControlFlowTest.kt` | JMP, JSR, RTS, RTI, BRK, NOP, SEC/CLC/etc tests |
| `knes-emulator/src/test/kotlin/knes/emulator/MemoryTest.kt` | Memory read/write/boundary tests |

### Modified Files

| File | Change |
|------|--------|
| `knes-emulator/build.gradle` | Add Kotest deps, `useJUnitPlatform()` |
| `knes-emulator/src/main/kotlin/knes/emulator/cpu/CPU.kt` | Add `step()` method, `singleStep` field, make `status` internal |

---

### Task 1: Configure Kotest Dependencies

**Files:**
- Modify: `knes-emulator/build.gradle`

- [ ] **Step 1: Add Kotest dependencies and JUnit Platform**

In `knes-emulator/build.gradle`, add Kotest test dependencies and enable JUnit Platform. The file currently has only `testImplementation 'junit:junit:4.13.2'`. Add after it:

```groovy
testImplementation 'io.kotest:kotest-runner-junit5:5.9.1'
testImplementation 'io.kotest:kotest-assertions-core:5.9.1'
testImplementation 'io.kotest:kotest-framework-datatest:5.9.1'
```

And add a `test` block if not present:

```groovy
test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :knes-emulator:dependencies --configuration testRuntimeClasspath | grep kotest`

Expected: Lines showing `io.kotest:kotest-runner-junit5:5.9.1`, `kotest-assertions-core`, `kotest-framework-datatest`

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/build.gradle
git commit -m "Add Kotest 5.9.1 test dependencies to knes-emulator"
```

---

### Task 2: Add CPU step() Support

**Files:**
- Modify: `knes-emulator/src/main/kotlin/knes/emulator/cpu/CPU.kt`

The CPU's `emulate()` method runs in an infinite loop. Tests need to execute exactly one instruction. We add a `singleStep` flag that stops the loop after one iteration, and make the `status` property `internal` so tests can read flag state.

- [ ] **Step 1: Add singleStep field**

In `CPU.kt`, after the `var crash: Boolean = false` line (around line 57), add:

```kotlin
var singleStep: Boolean = false
```

- [ ] **Step 2: Add singleStep check to emulate loop**

In `CPU.kt`, inside `emulate()`, just before the closing `}` of the `while (true)` loop (the comment says `// End of run loop.`, around line 1134), add:

```kotlin
            if (singleStep) {
                stopRunning = true
            }
```

This goes right after the `if (emulateSound)` block and before `} // End of run loop.`

- [ ] **Step 3: Add step() method**

After the `emulate()` method, add:

```kotlin
fun step() {
    singleStep = true
    stopRunning = false
    emulate()
    singleStep = false
}
```

- [ ] **Step 4: Make status property internal**

Change line 1241 from:

```kotlin
private var status: Int
```

to:

```kotlin
internal var status: Int
```

And change the setter from `private set(st)` to just `set(st)`.

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew :knes-emulator:compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add knes-emulator/src/main/kotlin/knes/emulator/cpu/CPU.kt
git commit -m "Add CPU step() method and internal status for testing"
```

---

### Task 3: Create Test Harness

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/TestMemoryAccess.kt`
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/CpuTestHarness.kt`

- [ ] **Step 1: Create TestMemoryAccess**

```kotlin
package knes.emulator.cpu

import knes.emulator.Memory
import knes.emulator.memory.MemoryAccess

class TestMemoryAccess(private val memory: Memory) : MemoryAccess {
    override fun load(address: Int): Short = memory.load(address and 0xFFFF)
    override fun write(address: Int, value: Short) { memory.write(address and 0xFFFF, value) }
}
```

- [ ] **Step 2: Create CpuTestHarness**

```kotlin
package knes.emulator.cpu

import knes.emulator.Memory
import knes.emulator.papu.PAPUClockFrame
import knes.emulator.ppu.PPUCycles
import knes.emulator.utils.Globals

class CpuTestHarness {
    val memory = Memory(0x10000)
    val cpu: CPU

    private val programBase = 0x8000

    init {
        // Disable PPU/PAPU callbacks during tests
        Globals.appletMode = false
        Globals.enableSound = false
        Globals.palEmulation = false

        val noopPapu = object : PAPUClockFrame {
            override fun clockFrameCounter(cycleCount: Int) {}
        }
        val noopPpu = object : PPUCycles {
            override fun setCycles(cycles: Int) {}
            override fun emulateCycles() {}
        }

        cpu = CPU(noopPapu, noopPpu)
        cpu.init(memory)
        cpu.setMapper(TestMemoryAccess(memory))
        cpu.reset()
        // Point PC to program base (PC+1 is where opcode is read)
        cpu.REG_PC_NEW = programBase - 1
    }

    /** Load instruction bytes at program base and execute one instruction. */
    fun execute(vararg bytes: Int) {
        for (i in bytes.indices) {
            memory.write(programBase + i, bytes[i].toShort())
        }
        cpu.REG_PC_NEW = programBase - 1
        cpu.step()
    }

    /** Execute N instructions starting from program base. */
    fun executeN(n: Int, vararg bytes: Int) {
        for (i in bytes.indices) {
            memory.write(programBase + i, bytes[i].toShort())
        }
        cpu.REG_PC_NEW = programBase - 1
        repeat(n) { cpu.step() }
    }

    /** Write a value to a memory address (for zero-page / absolute mode tests). */
    fun writeMem(address: Int, value: Int) {
        memory.write(address, value.toShort())
    }

    /** Read a value from a memory address. */
    fun readMem(address: Int): Int = memory.load(address).toInt() and 0xFF

    // Register accessors
    var a: Int
        get() = cpu.REG_ACC_NEW
        set(v) { cpu.REG_ACC_NEW = v }

    var x: Int
        get() = cpu.REG_X_NEW
        set(v) { cpu.REG_X_NEW = v }

    var y: Int
        get() = cpu.REG_Y_NEW
        set(v) { cpu.REG_Y_NEW = v }

    var sp: Int
        get() = cpu.REG_SP
        set(v) { cpu.REG_SP = v }

    var pc: Int
        get() = cpu.REG_PC_NEW
        set(v) { cpu.REG_PC_NEW = v }

    // Flag accessors (read from packed status byte)
    val carry: Boolean get() = (cpu.status and 0x01) != 0
    val zero: Boolean get() = (cpu.status and 0x02) != 0
    val interruptDisable: Boolean get() = (cpu.status and 0x04) != 0
    val decimal: Boolean get() = (cpu.status and 0x08) != 0
    val overflow: Boolean get() = (cpu.status and 0x40) != 0
    val negative: Boolean get() = (cpu.status and 0x80) != 0

    fun setCarry(v: Boolean) { cpu.status = if (v) cpu.status or 0x01 else cpu.status and 0x01.inv() }
    fun setZero(v: Boolean) { cpu.status = if (v) cpu.status or 0x02 else cpu.status and 0x02.inv() }
    fun setOverflow(v: Boolean) { cpu.status = if (v) cpu.status or 0x40 else cpu.status and 0x40.inv() }
    fun setNegative(v: Boolean) { cpu.status = if (v) cpu.status or 0x80 else cpu.status and 0x80.inv() }
    fun setInterruptDisable(v: Boolean) { cpu.status = if (v) cpu.status or 0x04 else cpu.status and 0x04.inv() }
    fun setDecimal(v: Boolean) { cpu.status = if (v) cpu.status or 0x08 else cpu.status and 0x08.inv() }
}
```

- [ ] **Step 3: Write a smoke test to verify harness works**

Create `knes-emulator/src/test/kotlin/knes/emulator/cpu/HarnessSmokeTest.kt`:

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HarnessSmokeTest : FunSpec({
    test("LDA immediate loads value into accumulator") {
        val h = CpuTestHarness()
        h.execute(0xA9, 0x42) // LDA #$42
        h.a shouldBe 0x42
        h.zero shouldBe false
        h.negative shouldBe false
    }

    test("LDA immediate zero sets zero flag") {
        val h = CpuTestHarness()
        h.execute(0xA9, 0x00) // LDA #$00
        h.a shouldBe 0x00
        h.zero shouldBe true
    }

    test("LDA immediate negative sets negative flag") {
        val h = CpuTestHarness()
        h.execute(0xA9, 0x80) // LDA #$80
        h.a shouldBe 0x80
        h.negative shouldBe true
    }
})
```

- [ ] **Step 4: Run the smoke test**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.HarnessSmokeTest" --info`

Expected: 3 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/TestMemoryAccess.kt
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/CpuTestHarness.kt
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/HarnessSmokeTest.kt
git commit -m "Add CPU test harness with smoke test"
```

---

### Task 4: CPU Arithmetic Tests (ADC, SBC)

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/ArithmeticTest.kt`

- [ ] **Step 1: Write ArithmeticTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class AdcCase(
    val desc: String,
    val a: Int, val value: Int, val carryIn: Boolean,
    val expected: Int, val carry: Boolean, val overflow: Boolean, val zero: Boolean, val negative: Boolean
)

data class SbcCase(
    val desc: String,
    val a: Int, val value: Int, val carryIn: Boolean,
    val expected: Int, val carry: Boolean, val overflow: Boolean, val zero: Boolean, val negative: Boolean
)

class ArithmeticTest : FunSpec({

    context("ADC immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                AdcCase("basic add", 0x10, 0x20, false, 0x30, false, false, false, false),
                AdcCase("add with carry in", 0x10, 0x20, true, 0x31, false, false, false, false),
                AdcCase("result zero", 0x00, 0x00, false, 0x00, false, false, true, false),
                AdcCase("carry out (0xFF + 0x01)", 0xFF, 0x01, false, 0x00, true, false, true, false),
                AdcCase("carry out (0x80 + 0x80)", 0x80, 0x80, false, 0x00, true, true, true, false),
                AdcCase("positive overflow (0x7F + 0x01)", 0x7F, 0x01, false, 0x80, false, true, false, true),
                AdcCase("negative result", 0x00, 0x80, false, 0x80, false, false, false, true),
                AdcCase("no overflow on different signs", 0x80, 0x01, false, 0x81, false, false, false, true),
                AdcCase("carry in causes carry out", 0xFF, 0x00, true, 0x00, true, false, true, false),
                AdcCase("carry in causes overflow", 0x7F, 0x00, true, 0x80, false, true, false, true),
                AdcCase("negative overflow (0x80 + 0xFF = -128 + -1)", 0x80, 0xFF, false, 0x7F, true, true, false, false),
                AdcCase("max no overflow", 0x3F, 0x40, false, 0x7F, false, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.setCarry(case.carryIn)
            h.execute(0x69, case.value) // ADC #imm
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.overflow shouldBe case.overflow
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ADC zero page") {
        test("reads value from zero page address") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.setCarry(false)
            h.writeMem(0x42, 0x20)
            h.execute(0x65, 0x42) // ADC $42
            h.a shouldBe 0x30
        }
    }

    context("ADC absolute") {
        test("reads value from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.setCarry(false)
            h.writeMem(0x0300, 0x20)
            h.execute(0x6D, 0x00, 0x03) // ADC $0300
            h.a shouldBe 0x30
        }
    }

    context("ADC zero page,X") {
        test("reads from (zp + X) with wrapping") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x05
            h.setCarry(false)
            h.writeMem(0x47, 0x20)
            h.execute(0x75, 0x42) // ADC $42,X
            h.a shouldBe 0x30
        }

        test("wraps around zero page") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x10
            h.setCarry(false)
            h.writeMem(0x0F, 0x20)
            h.execute(0x75, 0xFF) // ADC $FF,X -> wraps to $0F
            h.a shouldBe 0x30
        }
    }

    context("ADC absolute,X") {
        test("reads from (abs + X)") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x05
            h.setCarry(false)
            h.writeMem(0x0305, 0x20)
            h.execute(0x7D, 0x00, 0x03) // ADC $0300,X
            h.a shouldBe 0x30
        }
    }

    context("ADC absolute,Y") {
        test("reads from (abs + Y)") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.y = 0x05
            h.setCarry(false)
            h.writeMem(0x0305, 0x20)
            h.execute(0x79, 0x00, 0x03) // ADC $0300,Y
            h.a shouldBe 0x30
        }
    }

    context("ADC (indirect,X)") {
        test("reads from address pointed to by (zp+X)") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.x = 0x02
            h.setCarry(false)
            // Pointer at ZP $42+$02=$44, pointing to $0300
            h.writeMem(0x44, 0x00)
            h.writeMem(0x45, 0x03)
            h.writeMem(0x0300, 0x20)
            h.execute(0x61, 0x42) // ADC ($42,X)
            h.a shouldBe 0x30
        }
    }

    context("ADC (indirect),Y") {
        test("reads from address pointed to by (zp)+Y") {
            val h = CpuTestHarness()
            h.a = 0x10
            h.y = 0x05
            h.setCarry(false)
            // Pointer at ZP $42, pointing to $0300
            h.writeMem(0x42, 0x00)
            h.writeMem(0x43, 0x03)
            h.writeMem(0x0305, 0x20)
            h.execute(0x71, 0x42) // ADC ($42),Y
            h.a shouldBe 0x30
        }
    }

    context("SBC immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                SbcCase("basic subtract", 0x30, 0x10, true, 0x20, true, false, false, false),
                SbcCase("subtract with borrow (carry=0)", 0x30, 0x10, false, 0x1F, true, false, false, false),
                SbcCase("result zero", 0x10, 0x10, true, 0x00, true, false, true, false),
                SbcCase("borrow (result negative unsigned)", 0x10, 0x30, true, 0xE0, false, false, false, true),
                SbcCase("positive overflow (0x80 - 0x01)", 0x80, 0x01, true, 0x7F, true, true, false, false),
                SbcCase("negative overflow (0x7F - 0xFF)", 0x7F, 0xFF, true, 0x80, false, true, false, true),
                SbcCase("0x00 - 0x01 with carry", 0x00, 0x01, true, 0xFF, false, false, false, true),
                SbcCase("0xFF - 0xFF with carry", 0xFF, 0xFF, true, 0x00, true, false, true, false),
                SbcCase("0x00 - 0x00 no carry (borrow)", 0x00, 0x00, false, 0xFF, false, false, false, true),
                SbcCase("subtract zero", 0x42, 0x00, true, 0x42, true, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.setCarry(case.carryIn)
            h.execute(0xE9, case.value) // SBC #imm
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.overflow shouldBe case.overflow
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("SBC zero page") {
        test("reads value from zero page address") {
            val h = CpuTestHarness()
            h.a = 0x30
            h.setCarry(true)
            h.writeMem(0x42, 0x10)
            h.execute(0xE5, 0x42) // SBC $42
            h.a shouldBe 0x20
        }
    }

    context("SBC absolute") {
        test("reads value from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x30
            h.setCarry(true)
            h.writeMem(0x0300, 0x10)
            h.execute(0xED, 0x00, 0x03) // SBC $0300
            h.a shouldBe 0x20
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.ArithmeticTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/ArithmeticTest.kt
git commit -m "Add ADC and SBC instruction tests"
```

---

### Task 5: CPU Logical Tests (AND, ORA, EOR)

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/LogicalTest.kt`

- [ ] **Step 1: Write LogicalTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class LogicalCase(
    val desc: String,
    val a: Int, val value: Int,
    val expected: Int, val zero: Boolean, val negative: Boolean
)

class LogicalTest : FunSpec({

    context("AND immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                LogicalCase("basic AND", 0xFF, 0x0F, 0x0F, false, false),
                LogicalCase("result zero", 0xF0, 0x0F, 0x00, true, false),
                LogicalCase("result negative", 0xFF, 0x80, 0x80, false, true),
                LogicalCase("identity (AND with 0xFF)", 0x42, 0xFF, 0x42, false, false),
                LogicalCase("clear all (AND with 0x00)", 0xFF, 0x00, 0x00, true, false),
                LogicalCase("single bit", 0xAA, 0x55, 0x00, true, false),
                LogicalCase("high nibble", 0xAB, 0xF0, 0xA0, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.execute(0x29, case.value) // AND #imm
            h.a shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("AND zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x42, 0x0F)
            h.execute(0x25, 0x42) // AND $42
            h.a shouldBe 0x0F
        }
    }

    context("AND zero page,X") {
        test("reads from (zp + X)") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.x = 0x02
            h.writeMem(0x44, 0x0F)
            h.execute(0x35, 0x42) // AND $42,X
            h.a shouldBe 0x0F
        }
    }

    context("AND absolute") {
        test("reads from absolute address") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x0300, 0x0F)
            h.execute(0x2D, 0x00, 0x03) // AND $0300
            h.a shouldBe 0x0F
        }
    }

    context("ORA immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                LogicalCase("basic ORA", 0xF0, 0x0F, 0xFF, false, true),
                LogicalCase("result zero", 0x00, 0x00, 0x00, true, false),
                LogicalCase("identity (ORA with 0x00)", 0x42, 0x00, 0x42, false, false),
                LogicalCase("set all (ORA with 0xFF)", 0x00, 0xFF, 0xFF, false, true),
                LogicalCase("negative bit", 0x00, 0x80, 0x80, false, true),
                LogicalCase("no overlap", 0xAA, 0x55, 0xFF, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.execute(0x09, case.value) // ORA #imm
            h.a shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ORA zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0xF0
            h.writeMem(0x42, 0x0F)
            h.execute(0x05, 0x42) // ORA $42
            h.a shouldBe 0xFF
        }
    }

    context("EOR immediate") {
        withData(
            nameFn = { it.desc },
            listOf(
                LogicalCase("basic XOR", 0xFF, 0x0F, 0xF0, false, true),
                LogicalCase("result zero (same values)", 0xAA, 0xAA, 0x00, true, false),
                LogicalCase("identity (XOR with 0x00)", 0x42, 0x00, 0x42, false, false),
                LogicalCase("invert all (XOR with 0xFF)", 0xAA, 0xFF, 0x55, false, false),
                LogicalCase("single bit flip", 0x01, 0x01, 0x00, true, false),
                LogicalCase("high bit flip", 0x00, 0x80, 0x80, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.a
            h.execute(0x49, case.value) // EOR #imm
            h.a shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("EOR zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x42, 0x0F)
            h.execute(0x45, 0x42) // EOR $42
            h.a shouldBe 0xF0
        }
    }

    context("BIT zero page") {
        test("sets zero flag when AND is zero") {
            val h = CpuTestHarness()
            h.a = 0x0F
            h.writeMem(0x42, 0xF0)
            h.execute(0x24, 0x42) // BIT $42
            h.zero shouldBe true
            h.negative shouldBe true  // bit 7 of memory value
            h.overflow shouldBe true  // bit 6 of memory value
        }

        test("clears zero flag when AND is non-zero") {
            val h = CpuTestHarness()
            h.a = 0xFF
            h.writeMem(0x42, 0x3F)
            h.execute(0x24, 0x42) // BIT $42
            h.zero shouldBe false
            h.negative shouldBe false  // bit 7 = 0
            h.overflow shouldBe false  // bit 6 = 0
        }

        test("does not modify accumulator") {
            val h = CpuTestHarness()
            h.a = 0xAB
            h.writeMem(0x42, 0x00)
            h.execute(0x24, 0x42) // BIT $42
            h.a shouldBe 0xAB
        }
    }

    context("BIT absolute") {
        test("reads from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x0F
            h.writeMem(0x0300, 0xC0)
            h.execute(0x2C, 0x00, 0x03) // BIT $0300
            h.zero shouldBe true
            h.negative shouldBe true
            h.overflow shouldBe true
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.LogicalTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/LogicalTest.kt
git commit -m "Add AND, ORA, EOR, BIT instruction tests"
```

---

### Task 6: CPU Shift/Rotate Tests (ASL, LSR, ROL, ROR)

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/ShiftRotateTest.kt`

- [ ] **Step 1: Write ShiftRotateTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class ShiftCase(
    val desc: String,
    val input: Int, val carryIn: Boolean,
    val expected: Int, val carry: Boolean, val zero: Boolean, val negative: Boolean
)

class ShiftRotateTest : FunSpec({

    context("ASL accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("basic shift left", 0x01, false, 0x02, false, false, false),
                ShiftCase("shift into carry", 0x80, false, 0x00, true, true, false),
                ShiftCase("shift 0xFF", 0xFF, false, 0xFE, true, false, true),
                ShiftCase("zero stays zero", 0x00, false, 0x00, false, true, false),
                ShiftCase("0x40 becomes negative", 0x40, false, 0x80, false, false, true),
                ShiftCase("ignores carry in", 0x01, true, 0x02, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x0A) // ASL A
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ASL zero page") {
        test("shifts memory value") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0x40)
            h.execute(0x06, 0x42) // ASL $42
            h.readMem(0x42) shouldBe 0x80
            h.carry shouldBe false
            h.negative shouldBe true
        }

        test("carry out from memory") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0x80)
            h.execute(0x06, 0x42) // ASL $42
            h.readMem(0x42) shouldBe 0x00
            h.carry shouldBe true
            h.zero shouldBe true
        }
    }

    context("LSR accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("basic shift right", 0x02, false, 0x01, false, false, false),
                ShiftCase("shift into carry", 0x01, false, 0x00, true, true, false),
                ShiftCase("shift 0xFF", 0xFF, false, 0x7F, true, false, false),
                ShiftCase("zero stays zero", 0x00, false, 0x00, false, true, false),
                ShiftCase("always clears negative", 0x80, false, 0x40, false, false, false),
                ShiftCase("ignores carry in", 0x02, true, 0x01, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x4A) // LSR A
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("LSR zero page") {
        test("shifts memory value") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0x04)
            h.execute(0x46, 0x42) // LSR $42
            h.readMem(0x42) shouldBe 0x02
            h.carry shouldBe false
        }
    }

    context("ROL accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("rotate with carry=0", 0x01, false, 0x02, false, false, false),
                ShiftCase("rotate with carry=1", 0x01, true, 0x03, false, false, false),
                ShiftCase("rotate bit 7 into carry", 0x80, false, 0x00, true, true, false),
                ShiftCase("rotate bit 7 into carry, carry into bit 0", 0x80, true, 0x01, true, false, false),
                ShiftCase("0xFF with carry=0", 0xFF, false, 0xFE, true, false, true),
                ShiftCase("0xFF with carry=1", 0xFF, true, 0xFF, true, false, true),
                ShiftCase("zero with carry=0", 0x00, false, 0x00, false, true, false),
                ShiftCase("zero with carry=1", 0x00, true, 0x01, false, false, false),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x2A) // ROL A
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ROL zero page") {
        test("rotates memory value") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.writeMem(0x42, 0x40)
            h.execute(0x26, 0x42) // ROL $42
            h.readMem(0x42) shouldBe 0x81
            h.carry shouldBe false
        }
    }

    context("ROR accumulator") {
        withData(
            nameFn = { it.desc },
            listOf(
                ShiftCase("rotate with carry=0", 0x02, false, 0x01, false, false, false),
                ShiftCase("rotate with carry=1", 0x02, true, 0x81, false, false, true),
                ShiftCase("rotate bit 0 into carry", 0x01, false, 0x00, true, true, false),
                ShiftCase("rotate bit 0 into carry, carry into bit 7", 0x01, true, 0x80, true, false, true),
                ShiftCase("0xFF with carry=0", 0xFF, false, 0x7F, true, false, false),
                ShiftCase("0xFF with carry=1", 0xFF, true, 0xFF, true, false, true),
                ShiftCase("zero with carry=0", 0x00, false, 0x00, false, true, false),
                ShiftCase("zero with carry=1", 0x00, true, 0x80, false, false, true),
            )
        ) { case ->
            val h = CpuTestHarness()
            h.a = case.input
            h.setCarry(case.carryIn)
            h.execute(0x6A) // ROR A
            h.a shouldBe case.expected
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("ROR zero page") {
        test("rotates memory value") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.writeMem(0x42, 0x01)
            h.execute(0x66, 0x42) // ROR $42
            h.readMem(0x42) shouldBe 0x00
            h.carry shouldBe true
            h.zero shouldBe true
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.ShiftRotateTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/ShiftRotateTest.kt
git commit -m "Add ASL, LSR, ROL, ROR instruction tests"
```

---

### Task 7: CPU Branch Tests

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/BranchTest.kt`

- [ ] **Step 1: Write BranchTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BranchTest : FunSpec({

    // Note: branch offset is relative to the PC AFTER reading the 2-byte instruction.
    // With programBase=0x8000, PC after instruction = 0x8001 (since PC starts at 0x7FFF and advances by 2).
    // A forward offset of 0x05 jumps to 0x8001 + 0x05 = 0x8006.
    // A backward offset of 0x80 (-128) jumps to 0x8001 - 128 = 0x7F81.

    val programBase = 0x8000
    val pcAfterBranch = programBase + 1 // PC after 2-byte branch instruction read

    context("BCC - branch on carry clear") {
        test("branches when carry is clear") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.execute(0x90, 0x05) // BCC +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when carry is set") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.execute(0x90, 0x05) // BCC +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BCS - branch on carry set") {
        test("branches when carry is set") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.execute(0xB0, 0x05) // BCS +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when carry is clear") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.execute(0xB0, 0x05) // BCS +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BEQ - branch on zero set") {
        test("branches when zero flag is set") {
            val h = CpuTestHarness()
            h.setZero(true)
            h.execute(0xF0, 0x05) // BEQ +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when zero flag is clear") {
            val h = CpuTestHarness()
            h.setZero(false)
            h.execute(0xF0, 0x05) // BEQ +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BNE - branch on zero clear") {
        test("branches when zero flag is clear") {
            val h = CpuTestHarness()
            h.setZero(false)
            h.execute(0xD0, 0x05) // BNE +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when zero flag is set") {
            val h = CpuTestHarness()
            h.setZero(true)
            h.execute(0xD0, 0x05) // BNE +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BPL - branch on positive (sign clear)") {
        test("branches when negative flag is clear") {
            val h = CpuTestHarness()
            h.setNegative(false)
            h.execute(0x10, 0x05) // BPL +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when negative flag is set") {
            val h = CpuTestHarness()
            h.setNegative(true)
            h.execute(0x10, 0x05) // BPL +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BMI - branch on negative (sign set)") {
        test("branches when negative flag is set") {
            val h = CpuTestHarness()
            h.setNegative(true)
            h.execute(0x30, 0x05) // BMI +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when negative flag is clear") {
            val h = CpuTestHarness()
            h.setNegative(false)
            h.execute(0x30, 0x05) // BMI +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BVC - branch on overflow clear") {
        test("branches when overflow is clear") {
            val h = CpuTestHarness()
            h.setOverflow(false)
            h.execute(0x50, 0x05) // BVC +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when overflow is set") {
            val h = CpuTestHarness()
            h.setOverflow(true)
            h.execute(0x50, 0x05) // BVC +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("BVS - branch on overflow set") {
        test("branches when overflow is set") {
            val h = CpuTestHarness()
            h.setOverflow(true)
            h.execute(0x70, 0x05) // BVS +5
            h.pc shouldBe pcAfterBranch + 0x05
        }

        test("does not branch when overflow is clear") {
            val h = CpuTestHarness()
            h.setOverflow(false)
            h.execute(0x70, 0x05) // BVS +5
            h.pc shouldBe pcAfterBranch
        }
    }

    context("backward branch") {
        test("BNE branches backward with negative offset") {
            val h = CpuTestHarness()
            h.setZero(false)
            h.execute(0xD0, 0xFB) // BNE -5 (0xFB = -5 signed)
            h.pc shouldBe pcAfterBranch - 5
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.BranchTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/BranchTest.kt
git commit -m "Add branch instruction tests (BCC, BCS, BEQ, BNE, BPL, BMI, BVC, BVS)"
```

---

### Task 8: CPU Compare Tests (CMP, CPX, CPY)

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/CompareTest.kt`

- [ ] **Step 1: Write CompareTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class CmpCase(
    val desc: String,
    val reg: Int, val value: Int,
    val carry: Boolean, val zero: Boolean, val negative: Boolean
)

class CompareTest : FunSpec({

    val cmpCases = listOf(
        CmpCase("equal values", 0x42, 0x42, true, true, false),
        CmpCase("reg > value", 0x50, 0x30, true, false, false),
        CmpCase("reg < value", 0x30, 0x50, false, false, true),
        CmpCase("reg=0, value=0", 0x00, 0x00, true, true, false),
        CmpCase("reg=0xFF, value=0xFF", 0xFF, 0xFF, true, true, false),
        CmpCase("reg=0x80, value=0x7F", 0x80, 0x7F, true, false, false),
        CmpCase("reg=0x00, value=0x01", 0x00, 0x01, false, false, true),
        CmpCase("reg=0x01, value=0x00", 0x01, 0x00, true, false, false),
    )

    context("CMP immediate") {
        withData(nameFn = { it.desc }, cmpCases) { case ->
            val h = CpuTestHarness()
            h.a = case.reg
            h.execute(0xC9, case.value) // CMP #imm
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
            h.a shouldBe case.reg // CMP does not modify accumulator
        }
    }

    context("CMP zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.a = 0x42
            h.writeMem(0x10, 0x42)
            h.execute(0xC5, 0x10) // CMP $10
            h.carry shouldBe true
            h.zero shouldBe true
        }
    }

    context("CMP absolute") {
        test("reads from absolute address") {
            val h = CpuTestHarness()
            h.a = 0x50
            h.writeMem(0x0300, 0x30)
            h.execute(0xCD, 0x00, 0x03) // CMP $0300
            h.carry shouldBe true
            h.zero shouldBe false
        }
    }

    context("CPX immediate") {
        withData(nameFn = { it.desc }, cmpCases) { case ->
            val h = CpuTestHarness()
            h.x = case.reg
            h.execute(0xE0, case.value) // CPX #imm
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
            h.x shouldBe case.reg // CPX does not modify X
        }
    }

    context("CPX zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.x = 0x42
            h.writeMem(0x10, 0x42)
            h.execute(0xE4, 0x10) // CPX $10
            h.zero shouldBe true
        }
    }

    context("CPY immediate") {
        withData(nameFn = { it.desc }, cmpCases) { case ->
            val h = CpuTestHarness()
            h.y = case.reg
            h.execute(0xC0, case.value) // CPY #imm
            h.carry shouldBe case.carry
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
            h.y shouldBe case.reg // CPY does not modify Y
        }
    }

    context("CPY zero page") {
        test("reads from zero page") {
            val h = CpuTestHarness()
            h.y = 0x42
            h.writeMem(0x10, 0x42)
            h.execute(0xC4, 0x10) // CPY $10
            h.zero shouldBe true
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.CompareTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/CompareTest.kt
git commit -m "Add CMP, CPX, CPY instruction tests"
```

---

### Task 9: CPU Inc/Dec Tests

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/IncDecTest.kt`

- [ ] **Step 1: Write IncDecTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class IncDecCase(
    val desc: String,
    val input: Int,
    val expected: Int, val zero: Boolean, val negative: Boolean
)

class IncDecTest : FunSpec({

    val incCases = listOf(
        IncDecCase("basic increment", 0x10, 0x11, false, false),
        IncDecCase("increment to zero (wraparound)", 0xFF, 0x00, true, false),
        IncDecCase("increment to negative", 0x7F, 0x80, false, true),
        IncDecCase("increment zero", 0x00, 0x01, false, false),
        IncDecCase("increment 0xFE", 0xFE, 0xFF, false, true),
    )

    val decCases = listOf(
        IncDecCase("basic decrement", 0x10, 0x0F, false, false),
        IncDecCase("decrement to zero", 0x01, 0x00, true, false),
        IncDecCase("decrement zero (wraparound)", 0x00, 0xFF, false, true),
        IncDecCase("decrement negative to positive", 0x80, 0x7F, false, false),
        IncDecCase("decrement 0xFF", 0xFF, 0xFE, false, true),
    )

    context("INX") {
        withData(nameFn = { it.desc }, incCases) { case ->
            val h = CpuTestHarness()
            h.x = case.input
            h.execute(0xE8) // INX
            h.x shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("INY") {
        withData(nameFn = { it.desc }, incCases) { case ->
            val h = CpuTestHarness()
            h.y = case.input
            h.execute(0xC8) // INY
            h.y shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("DEX") {
        withData(nameFn = { it.desc }, decCases) { case ->
            val h = CpuTestHarness()
            h.x = case.input
            h.execute(0xCA) // DEX
            h.x shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("DEY") {
        withData(nameFn = { it.desc }, decCases) { case ->
            val h = CpuTestHarness()
            h.y = case.input
            h.execute(0x88) // DEY
            h.y shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("INC zero page") {
        withData(nameFn = { it.desc }, incCases) { case ->
            val h = CpuTestHarness()
            h.writeMem(0x42, case.input)
            h.execute(0xE6, 0x42) // INC $42
            h.readMem(0x42) shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("INC absolute") {
        test("increments memory at absolute address") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0x42)
            h.execute(0xEE, 0x00, 0x03) // INC $0300
            h.readMem(0x0300) shouldBe 0x43
        }
    }

    context("DEC zero page") {
        withData(nameFn = { it.desc }, decCases) { case ->
            val h = CpuTestHarness()
            h.writeMem(0x42, case.input)
            h.execute(0xC6, 0x42) // DEC $42
            h.readMem(0x42) shouldBe case.expected
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("DEC absolute") {
        test("decrements memory at absolute address") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0x42)
            h.execute(0xCE, 0x00, 0x03) // DEC $0300
            h.readMem(0x0300) shouldBe 0x41
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.IncDecTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/IncDecTest.kt
git commit -m "Add INC, DEC, INX, DEX, INY, DEY instruction tests"
```

---

### Task 10: CPU Load/Store Tests

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/LoadStoreTest.kt`

- [ ] **Step 1: Write LoadStoreTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class LoadCase(
    val desc: String,
    val value: Int,
    val zero: Boolean, val negative: Boolean
)

class LoadStoreTest : FunSpec({

    val loadCases = listOf(
        LoadCase("positive value", 0x42, false, false),
        LoadCase("zero", 0x00, true, false),
        LoadCase("negative value", 0x80, false, true),
        LoadCase("max positive", 0x7F, false, false),
        LoadCase("max value", 0xFF, false, true),
    )

    context("LDA immediate") {
        withData(nameFn = { it.desc }, loadCases) { case ->
            val h = CpuTestHarness()
            h.execute(0xA9, case.value) // LDA #imm
            h.a shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("LDA zero page") {
        test("loads from zero page") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0xAB)
            h.execute(0xA5, 0x42) // LDA $42
            h.a shouldBe 0xAB
        }
    }

    context("LDA zero page,X") {
        test("loads from (zp + X)") {
            val h = CpuTestHarness()
            h.x = 0x05
            h.writeMem(0x47, 0xAB)
            h.execute(0xB5, 0x42) // LDA $42,X
            h.a shouldBe 0xAB
        }
    }

    context("LDA absolute") {
        test("loads from absolute address") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0xAB)
            h.execute(0xAD, 0x00, 0x03) // LDA $0300
            h.a shouldBe 0xAB
        }
    }

    context("LDA absolute,X") {
        test("loads from (abs + X)") {
            val h = CpuTestHarness()
            h.x = 0x05
            h.writeMem(0x0305, 0xAB)
            h.execute(0xBD, 0x00, 0x03) // LDA $0300,X
            h.a shouldBe 0xAB
        }
    }

    context("LDA absolute,Y") {
        test("loads from (abs + Y)") {
            val h = CpuTestHarness()
            h.y = 0x05
            h.writeMem(0x0305, 0xAB)
            h.execute(0xB9, 0x00, 0x03) // LDA $0300,Y
            h.a shouldBe 0xAB
        }
    }

    context("LDA (indirect,X)") {
        test("loads from address at (zp+X)") {
            val h = CpuTestHarness()
            h.x = 0x02
            h.writeMem(0x44, 0x00)
            h.writeMem(0x45, 0x03)
            h.writeMem(0x0300, 0xAB)
            h.execute(0xA1, 0x42) // LDA ($42,X)
            h.a shouldBe 0xAB
        }
    }

    context("LDA (indirect),Y") {
        test("loads from (address at zp) + Y") {
            val h = CpuTestHarness()
            h.y = 0x05
            h.writeMem(0x42, 0x00)
            h.writeMem(0x43, 0x03)
            h.writeMem(0x0305, 0xAB)
            h.execute(0xB1, 0x42) // LDA ($42),Y
            h.a shouldBe 0xAB
        }
    }

    context("LDX immediate") {
        withData(nameFn = { it.desc }, loadCases) { case ->
            val h = CpuTestHarness()
            h.execute(0xA2, case.value) // LDX #imm
            h.x shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("LDX zero page") {
        test("loads from zero page") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0xAB)
            h.execute(0xA6, 0x42) // LDX $42
            h.x shouldBe 0xAB
        }
    }

    context("LDX zero page,Y") {
        test("loads from (zp + Y)") {
            val h = CpuTestHarness()
            h.y = 0x05
            h.writeMem(0x47, 0xAB)
            h.execute(0xB6, 0x42) // LDX $42,Y
            h.x shouldBe 0xAB
        }
    }

    context("LDY immediate") {
        withData(nameFn = { it.desc }, loadCases) { case ->
            val h = CpuTestHarness()
            h.execute(0xA0, case.value) // LDY #imm
            h.y shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("LDY zero page") {
        test("loads from zero page") {
            val h = CpuTestHarness()
            h.writeMem(0x42, 0xAB)
            h.execute(0xA4, 0x42) // LDY $42
            h.y shouldBe 0xAB
        }
    }

    context("LDY zero page,X") {
        test("loads from (zp + X)") {
            val h = CpuTestHarness()
            h.x = 0x05
            h.writeMem(0x47, 0xAB)
            h.execute(0xB4, 0x42) // LDY $42,X
            h.y shouldBe 0xAB
        }
    }

    context("STA zero page") {
        test("stores accumulator to zero page") {
            val h = CpuTestHarness()
            h.a = 0xAB
            h.execute(0x85, 0x42) // STA $42
            h.readMem(0x42) shouldBe 0xAB
        }
    }

    context("STA absolute") {
        test("stores accumulator to absolute address") {
            val h = CpuTestHarness()
            h.a = 0xAB
            h.execute(0x8D, 0x00, 0x03) // STA $0300
            h.readMem(0x0300) shouldBe 0xAB
        }
    }

    context("STA zero page,X") {
        test("stores to (zp + X)") {
            val h = CpuTestHarness()
            h.a = 0xAB
            h.x = 0x05
            h.execute(0x95, 0x42) // STA $42,X
            h.readMem(0x47) shouldBe 0xAB
        }
    }

    context("STX zero page") {
        test("stores X to zero page") {
            val h = CpuTestHarness()
            h.x = 0xAB
            h.execute(0x86, 0x42) // STX $42
            h.readMem(0x42) shouldBe 0xAB
        }
    }

    context("STX absolute") {
        test("stores X to absolute address") {
            val h = CpuTestHarness()
            h.x = 0xAB
            h.execute(0x8E, 0x00, 0x03) // STX $0300
            h.readMem(0x0300) shouldBe 0xAB
        }
    }

    context("STY zero page") {
        test("stores Y to zero page") {
            val h = CpuTestHarness()
            h.y = 0xAB
            h.execute(0x84, 0x42) // STY $42
            h.readMem(0x42) shouldBe 0xAB
        }
    }

    context("STY absolute") {
        test("stores Y to absolute address") {
            val h = CpuTestHarness()
            h.y = 0xAB
            h.execute(0x8C, 0x00, 0x03) // STY $0300
            h.readMem(0x0300) shouldBe 0xAB
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.LoadStoreTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/LoadStoreTest.kt
git commit -m "Add LDA, LDX, LDY, STA, STX, STY instruction tests"
```

---

### Task 11: CPU Stack Tests (PHA, PHP, PLA, PLP)

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/StackTest.kt`

- [ ] **Step 1: Write StackTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StackTest : FunSpec({

    context("PHA - push accumulator") {
        test("pushes A to stack and decrements SP") {
            val h = CpuTestHarness()
            h.a = 0x42
            val spBefore = h.sp
            h.execute(0x48) // PHA
            h.readMem(spBefore) shouldBe 0x42
            h.sp shouldBe (0x0100 or ((spBefore - 1) and 0xFF))
        }
    }

    context("PLA - pull accumulator") {
        test("pulls value from stack into A") {
            val h = CpuTestHarness()
            // Push a value first, then pull it
            h.a = 0x42
            h.executeN(2, 0x48, 0x68) // PHA, PLA
            h.a shouldBe 0x42
            h.zero shouldBe false
            h.negative shouldBe false
        }

        test("sets zero flag when pulling zero") {
            val h = CpuTestHarness()
            h.a = 0x00
            h.executeN(2, 0x48, 0x68) // PHA, PLA
            h.a shouldBe 0x00
            h.zero shouldBe true
        }

        test("sets negative flag when pulling negative") {
            val h = CpuTestHarness()
            h.a = 0x80
            h.executeN(2, 0x48, 0x68) // PHA, PLA
            h.a shouldBe 0x80
            h.negative shouldBe true
        }
    }

    context("PHP - push processor status") {
        test("pushes status flags to stack") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.setZero(true)
            h.setOverflow(true)
            val spBefore = h.sp
            h.execute(0x08) // PHP
            val pushed = h.readMem(spBefore)
            // PHP always sets break and unused flags in the pushed value
            (pushed and 0x01) shouldBe 1 // carry
            (pushed and 0x02) shouldBe 2 // zero
            (pushed and 0x10) shouldBe 0x10 // break (always set by PHP)
            (pushed and 0x20) shouldBe 0x20 // unused (always set)
            (pushed and 0x40) shouldBe 0x40 // overflow
        }
    }

    context("PLP - pull processor status") {
        test("restores flags from stack") {
            val h = CpuTestHarness()
            // Set some flags, push, clear, then pull to restore
            h.setCarry(true)
            h.setOverflow(true)
            h.setNegative(false)
            h.executeN(2,
                0x08, // PHP
                0x28  // PLP
            )
            h.carry shouldBe true
            h.overflow shouldBe true
        }
    }

    context("PHA/PLA round-trip") {
        test("multiple push/pull values are LIFO") {
            val h = CpuTestHarness()
            h.a = 0x11
            h.executeN(5,
                0x48,       // PHA (push 0x11)
                0xA9, 0x22, // LDA #$22
                0x48,       // PHA (push 0x22)
                0x68,       // PLA (pull 0x22)
            )
            h.a shouldBe 0x22
            // Pull second value
            h.execute(0x68) // PLA (pull 0x11)
            h.a shouldBe 0x11
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.StackTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/StackTest.kt
git commit -m "Add PHA, PHP, PLA, PLP stack instruction tests"
```

---

### Task 12: CPU Transfer Tests

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/TransferTest.kt`

- [ ] **Step 1: Write TransferTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

data class TransferCase(val desc: String, val value: Int, val zero: Boolean, val negative: Boolean)

class TransferTest : FunSpec({

    val transferCases = listOf(
        TransferCase("positive value", 0x42, false, false),
        TransferCase("zero", 0x00, true, false),
        TransferCase("negative value", 0x80, false, true),
        TransferCase("max value", 0xFF, false, true),
    )

    context("TAX - transfer A to X") {
        withData(nameFn = { it.desc }, transferCases) { case ->
            val h = CpuTestHarness()
            h.a = case.value
            h.execute(0xAA) // TAX
            h.x shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("TAY - transfer A to Y") {
        withData(nameFn = { it.desc }, transferCases) { case ->
            val h = CpuTestHarness()
            h.a = case.value
            h.execute(0xA8) // TAY
            h.y shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("TXA - transfer X to A") {
        withData(nameFn = { it.desc }, transferCases) { case ->
            val h = CpuTestHarness()
            h.x = case.value
            h.execute(0x8A) // TXA
            h.a shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("TYA - transfer Y to A") {
        withData(nameFn = { it.desc }, transferCases) { case ->
            val h = CpuTestHarness()
            h.y = case.value
            h.execute(0x98) // TYA
            h.a shouldBe case.value
            h.zero shouldBe case.zero
            h.negative shouldBe case.negative
        }
    }

    context("TSX - transfer SP to X") {
        test("transfers low byte of SP to X") {
            val h = CpuTestHarness()
            // SP is 0x01FF after reset, so low byte = 0xFF
            h.execute(0xBA) // TSX
            h.x shouldBe 0xFF
            h.negative shouldBe true
        }

        test("transfers SP after push") {
            val h = CpuTestHarness()
            h.a = 0x00
            h.executeN(2,
                0x48, // PHA (SP goes from 0x01FF to 0x01FE)
                0xBA  // TSX
            )
            h.x shouldBe 0xFE
        }
    }

    context("TXS - transfer X to SP") {
        test("transfers X to SP (does not affect flags)") {
            val h = CpuTestHarness()
            h.x = 0x80
            // Set flags to known state to verify TXS doesn't change them
            h.setZero(false)
            h.setNegative(false)
            h.execute(0x9A) // TXS
            h.sp shouldBe 0x0180
            // TXS does NOT affect any flags
            h.zero shouldBe false
            h.negative shouldBe false
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.TransferTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/TransferTest.kt
git commit -m "Add TAX, TAY, TXA, TYA, TSX, TXS transfer instruction tests"
```

---

### Task 13: CPU Control Flow Tests

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/cpu/ControlFlowTest.kt`

- [ ] **Step 1: Write ControlFlowTest**

```kotlin
package knes.emulator.cpu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ControlFlowTest : FunSpec({

    val programBase = 0x8000

    context("JMP absolute") {
        test("jumps to absolute address") {
            val h = CpuTestHarness()
            h.execute(0x4C, 0x00, 0x90) // JMP $9000
            // JMP sets PC to addr-1, then the loop increments won't happen in step mode
            // After step(), PC should be at the target address - 1
            h.pc shouldBe 0x9000 - 1
        }
    }

    context("JMP indirect") {
        test("jumps to address stored at pointer") {
            val h = CpuTestHarness()
            h.writeMem(0x0300, 0x00)
            h.writeMem(0x0301, 0x90)
            h.execute(0x6C, 0x00, 0x03) // JMP ($0300) -> jump to $9000
            h.pc shouldBe 0x9000 - 1
        }
    }

    context("JSR - jump to subroutine") {
        test("pushes return address and jumps") {
            val h = CpuTestHarness()
            val spBefore = h.sp
            h.execute(0x20, 0x00, 0x90) // JSR $9000
            h.pc shouldBe 0x9000 - 1
            // JSR pushes the address of the last byte of the JSR instruction
            // PC after reading JSR = programBase - 1 + 3 = programBase + 2
            // But JSR pushes REG_PC (which is PC after size advance = 0x8002)
            // High byte then low byte
            val pushedHi = h.readMem(spBefore)
            val pushedLo = h.readMem(0x0100 or ((spBefore - 1) and 0xFF))
            val returnAddr = (pushedHi shl 8) or pushedLo
            returnAddr shouldBe 0x8002
        }
    }

    context("RTS - return from subroutine") {
        test("pulls return address and jumps back") {
            val h = CpuTestHarness()
            // JSR to $9000, then RTS back
            // First write an RTS at $9000
            h.writeMem(0x9000, 0x60) // RTS
            h.execute(0x20, 0x00, 0x90) // JSR $9000
            // Now PC is at $8FFF. Step will execute RTS at $9000
            h.cpu.step()
            // RTS pulls address and adds 1, so PC should be back at $8002
            h.pc shouldBe 0x8002
        }
    }

    context("NOP") {
        test("does nothing") {
            val h = CpuTestHarness()
            h.a = 0x42
            h.x = 0x10
            h.y = 0x20
            val statusBefore = h.cpu.status
            h.execute(0xEA) // NOP
            h.a shouldBe 0x42
            h.x shouldBe 0x10
            h.y shouldBe 0x20
            h.cpu.status shouldBe statusBefore
        }
    }

    context("Flag instructions") {
        test("SEC sets carry") {
            val h = CpuTestHarness()
            h.setCarry(false)
            h.execute(0x38) // SEC
            h.carry shouldBe true
        }

        test("CLC clears carry") {
            val h = CpuTestHarness()
            h.setCarry(true)
            h.execute(0x18) // CLC
            h.carry shouldBe false
        }

        test("SED sets decimal") {
            val h = CpuTestHarness()
            h.setDecimal(false)
            h.execute(0xF8) // SED
            h.decimal shouldBe true
        }

        test("CLD clears decimal") {
            val h = CpuTestHarness()
            h.setDecimal(true)
            h.execute(0xD8) // CLD
            h.decimal shouldBe false
        }

        test("SEI sets interrupt disable") {
            val h = CpuTestHarness()
            h.setInterruptDisable(false)
            h.execute(0x78) // SEI
            h.interruptDisable shouldBe true
        }

        test("CLI clears interrupt disable") {
            val h = CpuTestHarness()
            h.setInterruptDisable(true)
            h.execute(0x58) // CLI
            h.interruptDisable shouldBe false
        }

        test("CLV clears overflow") {
            val h = CpuTestHarness()
            h.setOverflow(true)
            h.execute(0xB8) // CLV
            h.overflow shouldBe false
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.cpu.ControlFlowTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/cpu/ControlFlowTest.kt
git commit -m "Add JMP, JSR, RTS, NOP, and flag instruction tests"
```

---

### Task 14: Memory Tests

**Files:**
- Create: `knes-emulator/src/test/kotlin/knes/emulator/MemoryTest.kt`

- [ ] **Step 1: Write MemoryTest**

```kotlin
package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MemoryTest : FunSpec({

    test("write and read single byte") {
        val mem = Memory(0x10000)
        mem.write(0x0042, 0xAB.toShort())
        mem.load(0x0042) shouldBe 0xAB.toShort()
    }

    test("write and read at address 0x0000") {
        val mem = Memory(0x10000)
        mem.write(0x0000, 0xFF.toShort())
        mem.load(0x0000) shouldBe 0xFF.toShort()
    }

    test("write and read at last address") {
        val mem = Memory(0x10000)
        mem.write(0xFFFF, 0x42.toShort())
        mem.load(0xFFFF) shouldBe 0x42.toShort()
    }

    test("reset clears all memory") {
        val mem = Memory(0x100)
        mem.write(0x00, 0xAB.toShort())
        mem.write(0x50, 0xCD.toShort())
        mem.write(0xFF, 0xEF.toShort())
        mem.reset()
        mem.load(0x00) shouldBe 0.toShort()
        mem.load(0x50) shouldBe 0.toShort()
        mem.load(0xFF) shouldBe 0.toShort()
    }

    test("write array copies data") {
        val mem = Memory(0x100)
        val data = shortArrayOf(0x11, 0x22, 0x33, 0x44)
        mem.write(0x10, data, data.size)
        mem.load(0x10) shouldBe 0x11.toShort()
        mem.load(0x11) shouldBe 0x22.toShort()
        mem.load(0x12) shouldBe 0x33.toShort()
        mem.load(0x13) shouldBe 0x44.toShort()
    }

    test("write array with offset") {
        val mem = Memory(0x100)
        val data = shortArrayOf(0x11, 0x22, 0x33, 0x44)
        mem.write(0x10, data, 1, 2) // Write 2 bytes starting from index 1
        mem.load(0x10) shouldBe 0x22.toShort()
        mem.load(0x11) shouldBe 0x33.toShort()
    }

    test("write array does not overflow") {
        val mem = Memory(0x10)
        val data = shortArrayOf(0x11, 0x22, 0x33)
        mem.write(0x0F, data, data.size) // Only 1 byte fits, should be silently ignored
        // The write should be a no-op since address + length > memSize
        mem.load(0x0F) shouldBe 0.toShort()
    }

    test("state save and load roundtrip") {
        val mem = Memory(0x100)
        mem.write(0x00, 0xAB.toShort())
        mem.write(0x42, 0xCD.toShort())
        mem.write(0xFF, 0xEF.toShort())

        val buf = ByteBuffer(0x200, ByteBuffer.BO_BIG)
        mem.stateSave(buf)
        buf.goTo(0)

        val mem2 = Memory(0x100)
        mem2.stateLoad(buf)
        mem2.load(0x00) shouldBe 0xAB.toShort()
        mem2.load(0x42) shouldBe 0xCD.toShort()
        mem2.load(0xFF) shouldBe 0xEF.toShort()
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-emulator:test --tests "knes.emulator.MemoryTest" --info`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add knes-emulator/src/test/kotlin/knes/emulator/MemoryTest.kt
git commit -m "Add Memory unit tests"
```

---

### Task 15: Run Full Test Suite

This is a checkpoint task to verify everything works together.

- [ ] **Step 1: Run all tests**

Run: `./gradlew :knes-emulator:test --info`

Expected: All tests PASS. Check the test report for count — should be 150+ tests at this point.

- [ ] **Step 2: Clean up the old smoke test**

Delete `src/test/java/knes/SmokeTest.java` since it's now superseded by the comprehensive test suite.

- [ ] **Step 3: Verify clean build**

Run: `./gradlew clean :knes-emulator:test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git rm src/test/java/knes/SmokeTest.java
git commit -m "Remove placeholder smoke test, replaced by comprehensive test suite"
```

---

## Future Tasks (PPU, PAPU, Mappers, Controllers, ROM Integration)

The following tasks are outlined for the next phase of implementation. They follow the same pattern (Kotest FunSpec, real instances, no mocks) and can be planned in detail once the CPU test suite is stable.

### Task 16: PPU Register Tests
- Test PPUCTRL, PPUMASK, PPUSTATUS register read/write behavior
- Test scroll latch double-write mechanism ($2005/$2006)
- Test VBlank flag set/clear via PPUSTATUS reads

### Task 17: PPU VRAM/Mirroring Tests
- Test VRAM mirroring modes (horizontal, vertical, single-screen, four-screen)
- Test name table address resolution
- Test pattern table index calculation

### Task 18: PPU Sprite Tests
- Test OAM scan logic and sprite evaluation
- Test sprite-per-scanline limit (8 sprites)
- Test sprite 0 hit detection

### Task 19: PAPU Channel Tests
- Test ChannelSquare duty cycle, envelope, sweep
- Test ChannelTriangle linear counter, step sequencer
- Test ChannelNoise LFSR, mode switching
- Test ChannelDM sample buffer, DMA addressing

### Task 20: Mapper Tests
- Test MapperDefault address translation for all memory regions
- Test ROM bank loading
- Test battery RAM save/load

### Task 21: Controller Tests
- Move FM2 parser tests to proper `src/test/` location
- Test KeyboardController key mapping and state
- Test GamepadController button mapping

### Task 22: ROM Integration Test (nestest.nes)
- Load nestest.nes, set PC to $C000
- Run until halt, check result codes at $0002/$0003
- Optionally compare execution log against nestest.log
