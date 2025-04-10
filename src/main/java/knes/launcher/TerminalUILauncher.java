package knes.launcher;

/**
 * TerminalUILauncher - A standalone application that launches the kNES emulator with the Terminal UI.
 * 
 * This class provides a simple way to launch the Terminal UI implementation of the kNES emulator.
 * 
 * Note: The Terminal UI module is not included in the main project dependencies,
 * so this launcher attempts to use reflection to invoke the main method of the TerminalMain class.
 */
public class TerminalUILauncher {
    
    public static void main(String[] args) {
        // Set security manager with permissions
        System.setProperty("java.security.policy", "all.policy");
        
        System.out.println("Launching kNES with Terminal UI...");
        
        try {
            // Use reflection to invoke the main method of the TerminalMain class
            Class<?> terminalMainClass = Class.forName("knes.terminal.TerminalMainKt");
            java.lang.reflect.Method mainMethod = terminalMainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (ClassNotFoundException e) {
            System.err.println("Error: Terminal UI module not found in the classpath.");
            System.err.println("Please make sure the knes-terminal-ui module is included in the project dependencies.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error launching Terminal UI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}