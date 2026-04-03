# kNES - Kotlin NES Emulator

![image](https://github.com/user-attachments/assets/a2cc58bf-5a42-4f47-9b54-cfa6630cdb25)

kNES is a Nintendo Entertainment System (NES) emulator written in Kotlin, forked from the vNES Java emulator. This project was created primarily for fun and educational purposes, allowing developers to learn about emulation techniques and NES hardware while enjoying classic games.

![Tests](https://github.com/ArturSkowronski/kNES/actions/workflows/test.yml/badge.svg)

## About This Project

kNES is a reimplementation and extension of the vNES emulator (originally developed by Brian F. R.) in Kotlin. The project aims to:

- Provide a modern, Kotlin-based NES emulator
- Serve as an educational resource for those interested in emulation
- Demonstrate different UI implementation approaches in the JVM ecosystem
- Have fun with retro gaming and programming!

This project is distributed under the GNU General Public License v3.0 (GPL-3.0), ensuring it remains free and open source.

## Supported Mappers

| Mapper | Name | Games |
|--------|------|-------|
| 0 | NROM | Super Mario Bros, Donkey Kong, Pac-Man, ~250 games |
| 1 | MMC1/SxROM | Final Fantasy, The Legend of Zelda, Metroid, Mega Man 2, ~680 games |

## Controls

| Key | NES Button |
|-----|-----------|
| Z | A |
| X | B |
| Enter | Start |
| Space | Select |
| Arrow keys | D-pad |

Gamepad (Switch Joy-Con, Xbox-style controllers) also supported.

## Project Structure

The project is organized into the following modules:

- **knes-emulator**: Core emulator — CPU (6502), PPU, PAPU, memory, and mappers (NROM, MMC1).
- **knes-controllers**: Input handling — keyboard, gamepad (Switch Joy-Con, Xbox-style).
- **knes-compose-ui**: Jetpack Compose Desktop UI (primary, recommended).
- **knes-skiko-ui**: Skiko-based hardware-accelerated rendering UI.
- **knes-terminal-ui**: Terminal-based UI (text-based interface) — slow AF, but freaking fun.
- **knes-applet-ui**: Java Applet-based UI (legacy).

https://github.com/user-attachments/assets/9036ae9a-3be8-43ec-8050-3a47b29d1648

### KotlinConf 2025 Presentation: Build your own NES Emulator with Kotlin (click to play)

[![Build your own NES Emulator with Kotlin | Artur Skowroński](https://img.youtube.com/vi/4A6aLK2KznU/hqdefault.jpg)](https://www.youtube.com/watch?v=4A6aLK2KznU)


## Building and Running

### Prerequisites

- Java 17 or higher (for running Gradle; build targets Java 11)
- Gradle 9.4+ (included via wrapper)

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew run
```

This will launch the main application, which allows choosing between the different UI implementations.

### Running Specific UIs

You can run specific UI implementations directly:

```bash
# Applet UI
./gradlew :knes-applet-ui:run

# Compose UI
./gradlew :knes-compose-ui:run

# Terminal UI
./gradlew :knes-terminal-ui:run

# Skiko UI
./gradlew :knes-skiko-ui:run
```

## Architecture

The emulator uses a modular architecture with a clear separation between the core emulator functionality and the UI. This allows for different UI implementations to be used with the same core emulator.

### Core Emulator

The core emulator is contained in the `knes-emulator` module and provides the following components:

- **CPU**: 6502 processor — all 56 official opcodes, cycle-accurate
- **PPU**: Picture Processing Unit — background tiles, sprites, scrolling, palette
- **PAPU**: Audio — square, triangle, noise, and DMC channels
- **Memory**: 64KB CPU address space with mirroring
- **Mappers**: NROM (Mapper 0) and MMC1 (Mapper 1) with PRG/CHR bank switching

### Testing

390+ automated tests covering every layer:
- CPU instruction tests (all opcodes, all addressing modes)
- PPU register and rendering logic tests
- PAPU audio channel tests
- nestest.nes ROM integration test (community-standard CPU validation)
- Super Mario Bros E2E game tests (headless, input injection, RAM assertions)
- Compose Desktop UI smoke tests

```bash
./gradlew test
```

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license. This project is a reimplementation and extension of that work.
