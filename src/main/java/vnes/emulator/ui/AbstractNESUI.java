package vnes.emulator.ui;

import vnes.emulator.InputCallback;
import vnes.emulator.InputHandler;
import vnes.applet.input.InputHandlerAdapter;

/**
 * Abstract base implementation of the NESUICore interface.
 * This class provides common functionality for all UI implementations.
 */
public abstract class AbstractNESUI implements UiInfoMessageBus {
    protected DisplayBuffer displayBuffer;
    protected InputCallback[] inputCallbacks;
    protected InputHandler[] inputHandlers;
    
    public AbstractNESUI() {
        this.inputCallbacks = new InputCallback[2];
        this.inputHandlers = new InputHandler[2];
    }
    
    @Override
    public void destroy() {
        // Clean up resources
        if (displayBuffer != null) {
            displayBuffer.destroy();
            displayBuffer = null;
        }
        
        for (int i = 0; i < inputHandlers.length; i++) {
            if (inputHandlers[i] != null) {
                inputHandlers[i].reset();
                if (inputHandlers[i] instanceof InputHandlerAdapter) {
                    ((InputHandlerAdapter) inputHandlers[i]).destroy();
                }
                inputHandlers[i] = null;
            }
            inputCallbacks[i] = null;
        }
        
    }
}
