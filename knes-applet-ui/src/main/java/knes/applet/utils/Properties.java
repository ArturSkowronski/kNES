package knes.applet.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * POJO class to store all parameters from vNES.readParams.
 */
public class Properties {
    private String rom;
    private boolean scale;
    private boolean sound;
    private boolean stereo;
    private boolean scanlines;
    private boolean fps;
    private boolean timeemulation;
    private boolean showsoundbuffer;
    private int romSize;
    private Map<String, String> controls;

    /**
     * Default constructor with default values.
     */
    public Properties() {
        this.rom = "knes.nes";
        this.scale = false;
        this.sound = true;
        this.stereo = true;
        this.scanlines = false;
        this.fps = false;
        this.timeemulation = true;
        this.showsoundbuffer = false;
        this.romSize = -1;
        this.controls = new HashMap<>();
        
        // Default controls for Player 1
        controls.put("p1_up", "VK_UP");
        controls.put("p1_down", "VK_DOWN");
        controls.put("p1_left", "VK_LEFT");
        controls.put("p1_right", "VK_RIGHT");
        controls.put("p1_a", "VK_X");
        controls.put("p1_b", "VK_Z");
        controls.put("p1_start", "VK_ENTER");
        controls.put("p1_select", "VK_CONTROL");
        
        // Default controls for Player 2
        controls.put("p2_up", "VK_NUMPAD8");
        controls.put("p2_down", "VK_NUMPAD2");
        controls.put("p2_left", "VK_NUMPAD4");
        controls.put("p2_right", "VK_NUMPAD6");
        controls.put("p2_a", "VK_NUMPAD7");
        controls.put("p2_b", "VK_NUMPAD9");
        controls.put("p2_start", "VK_NUMPAD1");
        controls.put("p2_select", "VK_NUMPAD3");
    }

    // Getters and setters
    public String getRom() {
        return rom;
    }

    public void setRom(String rom) {
        this.rom = rom;
    }

    public boolean isScale() {
        return scale;
    }

    public void setScale(boolean scale) {
        this.scale = scale;
    }

    public boolean isSound() {
        return sound;
    }

    public void setSound(boolean sound) {
        this.sound = sound;
    }

    public boolean isStereo() {
        return stereo;
    }

    public void setStereo(boolean stereo) {
        this.stereo = stereo;
    }

    public boolean isScanlines() {
        return scanlines;
    }

    public void setScanlines(boolean scanlines) {
        this.scanlines = scanlines;
    }

    public boolean isFps() {
        return fps;
    }

    public void setFps(boolean fps) {
        this.fps = fps;
    }

    public boolean isTimeemulation() {
        return timeemulation;
    }

    public void setTimeemulation(boolean timeemulation) {
        this.timeemulation = timeemulation;
    }

    public boolean isShowsoundbuffer() {
        return showsoundbuffer;
    }

    public void setShowsoundbuffer(boolean showsoundbuffer) {
        this.showsoundbuffer = showsoundbuffer;
    }

    public int getRomSize() {
        return romSize;
    }

    public void setRomSize(int romSize) {
        this.romSize = romSize;
    }

    public Map<String, String> getControls() {
        return controls;
    }

    public void setControls(Map<String, String> controls) {
        this.controls = controls;
    }
}