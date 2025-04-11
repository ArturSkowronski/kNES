# kNES - Kotlin NES Emulator

![image](https://github.com/user-attachments/assets/a2cc58bf-5a42-4f47-9b54-cfa6630cdb25)

kNES is a Nintendo Entertainment System (NES) emulator written in Kotlin, forked from the vNES Java emulator. This project was created primarily for fun and educational purposes, allowing developers to learn about emulation techniques and NES hardware while enjoying classic games.

![Gradle Build](https://github.com/ArturSkowronski/kNES/actions/workflows/build.yml/badge.svg)

## About This Project

kNES is a reimplementation and extension of the vNES emulator (originally developed by Brian F. R.) in Kotlin. The project aims to:

- Provide a modern, Kotlin-based NES emulator
- Serve as an educational resource for those interested in emulation
- Demonstrate different UI implementation approaches in the JVM ecosystem
- Have fun with retro gaming and programming!

This project is distributed under the GNU General Public License v3.0 (GPL-3.0), ensuring it remains free and open source.

## Current Limitations

- Supports only basic mapper (will elaborate more in the future, why)
- Do not clean memory upon start, which messes up some games (check releases)... but this is a feature I want to build upon in future ;) 

## Project Structure

The project is organized into the following modules:

- **knes-emulator**: Core emulator functionality, including CPU, PPU, memory, and mappers.
- **knes-applet-ui**: Java Applet-based UI for the emulator (legacy support).
- **knes-compose-ui**: Jetpack Compose-based UI for the emulator (modern desktop UI).
- **knes-terminal-ui**: Terminal-based UI for the emulator (text-based interface) - slow AF, but freaking fun.
- **knes-skiko-ui**: Skiko-based UI for the emulator (Kotlin multiplatform graphics).

https://github.com/user-attachments/assets/9036ae9a-3be8-43ec-8050-3a47b29d1648

## Building and Running

### Prerequisites

- Java 17 or higher
- Gradle 7.0 or higher

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

- **CPU**: 6502 CPU emulation
- **PPU**: Picture Processing Unit emulation
- **Memory**: Memory management
- **Mappers**: ROM mappers for different game cartridges

### UI Abstraction

The UI abstraction is provided by the `NESUIFactory` interface, which allows different UI implementations to be plugged into the core emulator. The interface provides methods for creating UI components such as input handlers and screen views.
 
## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license. This project is a reimplementation and extension of that work.
