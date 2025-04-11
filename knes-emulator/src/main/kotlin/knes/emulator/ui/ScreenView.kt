/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.emulator.ui

/**
 * Platform-agnostic interface for screen display operations.
 * This interface defines methods for manipulating and displaying the NES screen
 * without dependencies on specific UI frameworks.
 */
interface ScreenView {

    /**
     * Initialize the screen view.
     */
    fun init()

    /**
     * Get the buffer of pixel data for the screen.
     *
     * @return Array of pixel data in RGB format
     */
    fun getBuffer(): IntArray

    /**
     * Get the width of the buffer.
     *
     * @return The width in pixels
     */
    fun getBufferWidth(): Int

    /**
     * Get the height of the buffer.
     *
     * @return The height in pixels
     */
    fun getBufferHeight(): Int

    /**
     * Notify that an image is ready to be displayed.
     *
     * @param skipFrame Whether this frame should be skipped
     */
    fun imageReady(skipFrame: Boolean)

    /**
     * Check if scaling is enabled for this screen view.
     *
     * @return true if scaling is enabled, false otherwise
     */
    fun scalingEnabled(): Boolean

    /**
     * Check if hardware scaling is being used.
     *
     * @return true if hardware scaling is being used, false otherwise
     */
    fun useHWScaling(): Boolean

    /**
     * Get the current scale mode.
     *
     * @return The current scale mode
     */
    fun getScaleMode(): Int

    /**
     * Set the scale mode for the screen view.
     *
     * @param newMode The new scale mode
     */
    fun setScaleMode(newMode: Int)

    /**
     * Get the scale factor for a given scale mode.
     *
     * @param mode The scale mode
     * @return The scale factor
     */
    fun getScaleModeScale(mode: Int): Int

    /**
     * Set whether to show the FPS counter.
     *
     * @param enabled true to show FPS, false to hide
     */
    fun setFPSEnabled(enabled: Boolean)

    /**
     * Set the background color.
     *
     * @param color The background color in RGB format
     */
    fun setBgColor(color: Int)

    /**
     * Clean up resources used by this screen view.
     */
    fun destroy()
}