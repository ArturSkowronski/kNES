# vNES Project Guidelines

## Project Overview

vNES is a Nintendo Entertainment System (NES) emulator implemented in Java. It allows users to play NES games on modern computers by emulating the hardware of the original Nintendo Entertainment System. The emulator is implemented as a Java applet, which can be run either in a compatible browser or as a standalone application using the provided AppletLauncher.

### Key Features

- NES hardware emulation
- Support for loading and playing NES ROM files
- Java-based implementation for cross-platform compatibility
- Multiple ways to run the emulator (standalone, applet, etc.)

## Technical Architecture

The project is structured as follows:

- `src/main/java/` - Java source files containing the emulator implementation
- `src/main/resources/` - Resource files (palettes)
- `roms/` - Directory for storing NES ROM files (not included in the repository)
- `build.gradle` - Gradle build configuration
- `settings.gradle` - Gradle settings
- `all.policy` - Java security policy file for running the applet

## Development Guidelines

### Environment Setup

- **Java Version**: Java 8 (JDK 1.8) is required as it's the last Java version with full applet support
- **Build System**: Gradle (wrapper included in the repository)
- **IDE**: Any Java IDE with Gradle support (IntelliJ IDEA, Eclipse, etc.)

### Building the Project

To build the project:

```
./gradlew build
```

This will compile the Java sources and create a JAR file in `build/libs/vNES.jar`.

### Running the Application

There are multiple ways to run the application:

1. **Using Gradle run task (recommended)**:
   ```
   ./gradlew run
   ```

2. **Running the JAR file directly**:
   ```
   java -jar build/libs/vNES.jar
   ```

3. **Using Gradle runApplet task** (requires Java 8 with appletviewer):
   ```
   ./gradlew runApplet
   ```

4. **Using a Java 8 compatible browser** (requires Java 8):
   After running the build task, an HTML file is generated at `build/applet.html`. You can open this file in a browser that supports Java applets (with the Java plugin enabled).

### Using ROM Files

To use the emulator, you need to provide NES ROM files:

1. Create a `roms` directory in the project root (if not already created)
2. Place your NES ROM files (`.nes` files) in the `roms` directory
3. When running the application, you can load a ROM by:
   - Placing a ROM file named `vnes.nes` in the project root directory, or
   - Using the file chooser in the application to select a ROM file

### Continuous Integration

This project uses GitHub Actions for continuous integration. The workflow:

- Runs on every push and pull request
- Builds the project with Gradle
- Runs tests to verify functionality
- Uses Java 8 (JDK 1.8) for compatibility

## Contributing

When contributing to this project, please follow these guidelines:

1. Create a feature branch for your changes
2. Follow Java coding conventions
3. Write tests for new functionality
4. Update documentation as needed
5. Submit a pull request for review

## Notes and Limitations

- Java applets are deprecated technology and may not work in modern browsers
- This project is configured to use Java 8, which is the last version with full applet support
- NES ROM files are not included with this project. You must obtain them legally elsewhere.