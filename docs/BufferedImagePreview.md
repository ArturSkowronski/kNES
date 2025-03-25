# How to Preview BufferedImage in vNES

This guide explains different ways to preview a BufferedImage in the vNES project.

## Overview

In the vNES project, BufferedImage objects are used as an intermediate step in the rendering pipeline. They are created from pixel data stored in IntArrays and then converted to ImageBitmap objects for display in the Compose UI.

There are several ways to preview these BufferedImage objects:

1. Using the ImagePreview utility class (recommended)
2. Saving the image to a file using ScreenLogger
3. Converting to ImageBitmap and displaying in the Compose UI (already implemented in the project)

## Method 1: Using the ImagePreview Utility Class

The `ImagePreview` utility class provides simple methods to display a BufferedImage in a Swing window. This is the most straightforward way to preview a BufferedImage during development or debugging.

### Simple Preview

```java
// Get a BufferedImage from somewhere in your code
BufferedImage image = getBufferedImage();

// Display it in a simple window
ImagePreview.show(image);

// Or with a custom title
ImagePreview.show(image, "My Image Preview");
```

### Preview with Zoom Controls

```java
// Get a BufferedImage from somewhere in your code
BufferedImage image = getBufferedImage();

// Display it in a window with zoom controls
ImagePreview.showWithZoom(image, "Zoomable Image Preview");
```

## Method 2: Saving to a File using ScreenLogger

The `ScreenLogger` class provides a method to save a BufferedImage to a file, which you can then view using any image viewer.

```java
// Get a BufferedImage from somewhere in your code
BufferedImage image = getBufferedImage();

// Save it to a file
// Note: ScreenLogger is a Kotlin object, so in Java we access it via INSTANCE
vnes.compose.ScreenLogger.INSTANCE.logFrameImage(image, "frame.jpg", "debug");

// The image will be saved to debug/frame.jpg
```

## Method 3: Using the Existing Compose UI

The vNES project already has code to display BufferedImage objects in the Compose UI. This is done by converting the BufferedImage to an ImageBitmap using the `toComposeImageBitmap()` extension function.

```kotlin
// In Kotlin
val image: BufferedImage = getBufferedImage()
val imageBitmap = image.toComposeImageBitmap()

// Then use imageBitmap in a Compose UI
Canvas(modifier = Modifier.size(width.dp, height.dp)) {
    drawImage(image = imageBitmap)
}
```

## Example: Getting a BufferedImage from ComposeScreenView

Here's how to get a BufferedImage from a ComposeScreenView:

```java
private static BufferedImage getBufferedImageFromScreenView(ComposeScreenView screenView) {
    // Get the buffer from the screen view
    int[] buffer = screenView.getBuffer();
    int width = screenView.getBufferWidth();
    int height = screenView.getBufferHeight();
    
    // Create a new BufferedImage
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    
    // Set the RGB values from the buffer
    image.setRGB(0, 0, width, height, buffer, 0, width);
    
    return image;
}
```

## Complete Example

See the `BufferedImagePreviewExample.java` file for a complete example that demonstrates all these methods.

To run the example:

```
java -cp build/libs/vNES.jar vnes.examples.BufferedImagePreviewExample
```

## Additional Notes

- The ImagePreview utility class is designed for development and debugging purposes.
- For production use, consider using the existing Compose UI infrastructure.
- When working with large images or many images, be mindful of memory usage.