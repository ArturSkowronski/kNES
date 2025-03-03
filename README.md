# vNES - NES Emulator

## Building with Gradle

This project uses Gradle to build and run the NES emulator as a Java applet.

### Requirements

- Java 8 (JDK 1.8) - the last Java version with full applet support
- Gradle (wrapper included)

### Building

To build the project:

```
./gradlew build
```

This will compile the Java sources and create a JAR file in `build/libs/vNES.jar`.

### Running the Application

There are multiple ways to run the application:

1. Using the Gradle run task (recommended):

```
./gradlew run
```

This will run the application as a standalone Java application using the AppletLauncher class, which embeds the applet in a JFrame.

2. Running the JAR file directly:

```
java -jar build/libs/vNES.jar
```

3. Using the Gradle runApplet task (requires Java 8 with appletviewer):

```
./gradlew runApplet
```

This will attempt to run the applet using appletviewer if available.

4. Using a Java 8 compatible browser (requires Java 8):

After running the build task, an HTML file is generated at `build/applet.html`. You can open this file in a browser that supports Java applets (with the Java plugin enabled).

### Project Structure

- `src/main/java/` - Java source files
- `src/main/resources/` - Resource files (palettes)
- `build.gradle` - Gradle build configuration
- `settings.gradle` - Gradle settings
- `all.policy` - Java security policy file for running the applet

### Using ROM Files

To use the emulator, you need to provide NES ROM files:

1. Create a `roms` directory in the project root (if not already created)
2. Place your NES ROM files (`.nes` files) in the `roms` directory
3. When running the application, you can load a ROM by:
   - Placing a ROM file named `vnes.nes` in the project root directory, or
   - Modifying the `AppletLauncher.java` file to specify a different ROM file:
     ```java
     // In AppletStubImpl constructor
     parameters.put("ROM", "your-rom-filename.nes");
     ```

### Notes

- Java applets are deprecated technology and may not work in modern browsers
- This project is configured to use Java 8, which is the last version with full applet support
- NES ROM files are not included with this project. You must obtain them legally elsewhere.
