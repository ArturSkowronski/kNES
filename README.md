# kNES - Kotlin NES Emulator

vNES is a Nintendo Entertainment System (NES) emulator written in Java and Kotlin.

## Project Structure

The project is organized into the following modules:

- **knes-emulator**: Core emulator functionality, including CPU, PPU, memory, and mappers.
- **knes-applet-ui**: Java Applet-based UI for the emulator.
- **knes-compose-ui**: Jetpack Compose-based UI for the emulator.
- **Main Module**: Launcher application that allows choosing between different UIs.

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

This will launch the main application, which allows choosing between the Applet UI and the Compose UI.

### Running the Applet UI directly

```bash
./gradlew :knes-applet-ui:run
```

### Running the Compose UI directly

```bash
./gradlew :knes-compose-ui:run
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

### UI Implementations

The project provides two UI implementations:

- **Applet UI**: A Java Applet-based UI for the emulator.
- **Compose UI**: A Jetpack Compose-based UI for the emulator.

## License

This project is licensed under the GNU General Public License v3.0 - see the LICENSE file for details.
