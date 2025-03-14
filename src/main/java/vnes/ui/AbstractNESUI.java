package vnes.ui;

import vnes.input.InputCallback;
import vnes.input.InputHandler;
import vnes.input.InputHandlerAdapter;
import vnes.NES;

/**
 * Abstract base implementation of the NESUICore interface.
 * This class provides common functionality for all UI implementations.
 */
public abstract class AbstractNESUI implements NESUICore {
    protected NES nes;
    protected DisplayBuffer displayBuffer;
    protected InputCallback[] inputCallbacks;
    protected InputHandler[] inputHandlers;
    
    /**
     * Create a new AbstractNESUI.
     * 
     * @param nes The NES instance to use
     */
    public AbstractNESUI(NES nes) {
        this.nes = nes;
        this.inputCallbacks = new InputCallback[2];
        this.inputHandlers = new InputHandler[2];
    }
    
    @Override
    public NES getNES() {
        return nes;
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
        
        nes = null;
    }
}
