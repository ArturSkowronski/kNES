#!/bin/bash

# Check if Java 8 is available
if command -v /usr/libexec/java_home -v 1.8 &> /dev/null; then
    JAVA8_HOME=$(/usr/libexec/java_home -v 1.8)
    echo "Found Java 8 at: $JAVA8_HOME"
    
    # Use Java 8's appletviewer if available
    if [ -f "$JAVA8_HOME/bin/appletviewer" ]; then
        echo "Running applet with Java 8's appletviewer..."
        "$JAVA8_HOME/bin/appletviewer" -J-Djava.security.policy=all.policy build/applet.html
        exit $?
    fi
fi

# If we get here, we couldn't find Java 8 or its appletviewer
echo "Java 8 or appletviewer not found."
echo "This application requires Java 8 (1.8) to run as an applet."
echo "Please install Java 8 JDK and try again."
echo ""
echo "Current Java version:"
java -version
echo ""
echo "You can still build the project with: ./gradlew build"
echo "The JAR file will be created at: build/libs/kNES.jar"

exit 1
