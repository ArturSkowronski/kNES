package vnes.compose

/*
vNES
Copyright © 2006-2013 Open Emulation Project

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Main entry point for the Compose UI.
 * 
 * Note: This is a temporary implementation using Swing instead of Compose
 * until the Compose UI dependencies are properly configured.
 */
fun main() {
    SwingUtilities.invokeLater {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val frame = JFrame("vNES Emulator")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.size = Dimension(800, 600)
        frame.setLocationRelativeTo(null)

        val mainPanel = JPanel(BorderLayout())

        // Title
        val titleLabel = JLabel("vNES Emulator - Compose UI", JLabel.CENTER)
        titleLabel.font = Font("Arial", Font.BOLD, 24)
        mainPanel.add(titleLabel, BorderLayout.NORTH)

        // Center panel with buttons
        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        centerPanel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

        var isEmulatorRunning = false
        val startStopButton = JButton("Start Emulator")
        startStopButton.alignmentX = JButton.CENTER_ALIGNMENT
        startStopButton.addActionListener {
            isEmulatorRunning = !isEmulatorRunning
            startStopButton.text = if (isEmulatorRunning) "Stop Emulator" else "Start Emulator"
        }
        centerPanel.add(startStopButton)

        // Add some space
        centerPanel.add(JPanel().apply { 
            preferredSize = Dimension(0, 20)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 20)
        })

        val loadRomButton = JButton("Load ROM")
        loadRomButton.alignmentX = JButton.CENTER_ALIGNMENT
        loadRomButton.addActionListener {
            // Load ROM functionality
        }
        centerPanel.add(loadRomButton)

        mainPanel.add(centerPanel, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val statusLabel = JLabel("Ready")
        statusPanel.add(statusLabel)
        mainPanel.add(statusPanel, BorderLayout.SOUTH)

        frame.contentPane = mainPanel
        frame.isVisible = true
    }
}
