package vnes.compose.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.max

/**
 * Utility class for previewing image objects.
 * This class provides methods to display BufferedImage and ComposeImageBitmap objects.
 */
class ImagePreview {
    /**
     * Displays a BufferedImage in a new window.
     *
     * @param image The BufferedImage to display
     * @param title The title of the window (default: "Image Preview")
     */
    /**
     * Displays a BufferedImage in a new window with the default title.
     *
     * @param image The BufferedImage to display
     */
    @JvmOverloads
    fun show(image: BufferedImage?, title: String? = "Image Preview") {
        SwingUtilities.invokeLater(Runnable {
            val frame = JFrame(title)
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)

            // Create a panel to display the image
            val panel: JPanel = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    if (image != null) {
                        g.drawImage(image, 0, 0, this.getWidth(), this.getHeight(), this)
                    }
                }
            }

            // Set preferred size based on image dimensions
            panel.setPreferredSize(
                Dimension(
                    max(320.0, image!!.getWidth().toDouble()).toInt(),
                    max(240.0, image.getHeight().toDouble()).toInt()
                )
            )

            frame.add(panel)
            frame.pack()
            frame.setLocationRelativeTo(null) // Center on screen
            frame.setVisible(true)
        })
    }

    /**
     * A custom panel that displays an image with zoom capability.
     */
    private class ZoomableImagePanel(image: BufferedImage) : JPanel() {
        private val image: BufferedImage?
        private var zoomFactor = 1.0

        init {
            this.image = image
            setPreferredSize(
                Dimension(
                    max(320.0, image.getWidth().toDouble()).toInt(),
                    max(240.0, image.getHeight().toDouble()).toInt()
                )
            )
        }

        override fun paintComponent(g: Graphics?) {
            super.paintComponent(g)
            if (image != null) {
                val g2d = g as Graphics2D
                g2d.scale(zoomFactor, zoomFactor)
                g2d.drawImage(image, 0, 0, this)
            }
        }

        fun setZoomFactor(factor: Double) {
            this.zoomFactor = factor
            repaint()
        }

        fun getZoomFactor(): Double {
            return zoomFactor
        }
    }

    /**
     * Displays a ComposeImageBitmap in a new window using Compose UI.
     *
     * @param image The ComposeImageBitmap to display
     * @param title The title of the window (default: "Image Preview")
     */
    @JvmOverloads
    fun showCompose(image: ImageBitmap, title: String = "Image Preview") {
        Thread {application {
            val windowState = rememberWindowState(width = 800.dp, height = 600.dp)

            Window(
                onCloseRequest = ::exitApplication,
                title = title,
                state = windowState
            ) {
                MaterialTheme {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = "Preview Image",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }.start()}

    /**
     * Displays a ComposeImageBitmap in a new window with zoom controls using Compose UI.
     * This implementation uses a separate thread to avoid blocking the main thread.
     *
     * @param image The ComposeImageBitmap to display
     * @param title The title of the window (default: "Image Preview with Zoom")
     */
    @JvmOverloads
    fun showComposeWithZoom(image: ImageBitmap, title: String = "Image Preview with Zoom") {
        // Launch the preview in a separate thread to avoid blocking the main thread
        Thread {
            application {
                val windowState = rememberWindowState(width = 800.dp, height = 600.dp)
                var scale by remember { mutableStateOf(1f) }

                Window(
                    onCloseRequest = ::exitApplication,
                    title = title,
                    state = windowState
                ) {
                    MaterialTheme {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        ) {
                            // Zoom controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(onClick = { scale = (scale * 1.2f).coerceAtMost(5f) }) {
                                    Text("+")
                                }

                                Slider(
                                    value = scale,
                                    onValueChange = { scale = it },
                                    valueRange = 0.1f..5f,
                                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                                )

                                Button(onClick = { scale = (scale * 0.8f).coerceAtLeast(0.1f) }) {
                                    Text("-")
                                }

                                Button(onClick = { scale = 1f }) {
                                    Text("Reset")
                                }
                            }

                            // Image display
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = image,
                                    contentDescription = "Preview Image",
                                    modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
                                )
                            }
                        }
                    }
                }
            }
        }.start()
    }
}
