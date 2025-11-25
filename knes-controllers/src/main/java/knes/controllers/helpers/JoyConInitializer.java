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

package knes.controllers.helpers;

import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;
import org.hid4java.ScanMode;

import java.util.List;

public class JoyConInitializer {

    private static final int NINTENDO_VENDOR_ID = 0x057E;
    private static final int JOYCON_L_PRODUCT_ID = 0x2006;
    private static final int JOYCON_R_PRODUCT_ID = 0x2007;

    private static int globalPacketCounter = 0;

    public static void initializeJoyCons() {
        System.out.println("[JoyConInitializer] Scanning for Joy-Cons...");
        
        // Configure HID services
        HidServicesSpecification hidServicesSpecification = new HidServicesSpecification();
        hidServicesSpecification.setAutoShutdown(true);
        hidServicesSpecification.setScanInterval(500);
        hidServicesSpecification.setPauseInterval(5000);
        hidServicesSpecification.setScanMode(ScanMode.SCAN_AT_FIXED_INTERVAL_WITH_PAUSE_AFTER_WRITE);

        HidServices hidServices = HidManager.getHidServices(hidServicesSpecification);
        hidServices.start();

        // Scan for devices
        List<HidDevice> devices = hidServices.getAttachedHidDevices();
        boolean found = false;

        for (HidDevice device : devices) {
            if (device.getVendorId() == NINTENDO_VENDOR_ID && 
               (device.getProductId() == JOYCON_L_PRODUCT_ID || device.getProductId() == JOYCON_R_PRODUCT_ID)) {
                
                System.out.println("[JoyConInitializer] Found Joy-Con: " + device.getProduct() + " (Product ID: 0x" + Integer.toHexString(device.getProductId()) + ")");
                initializeDevice(device);
                found = true;
            }
        }

        if (!found) {
            System.out.println("[JoyConInitializer] No Joy-Cons found.");
        }
        
        // We can shut down the HID services now as we only needed it for initialization
        // Note: In a real driver we might keep it open to read inputs, but here we just want to wake them up
        // so that the OS/JVM can pick them up via standard Gamepad APIs (like gdx-controllers).
        // However, gdx-controllers might conflict if we keep the device open exclusively? 
        // hid4java usually opens in shared mode if possible, but let's close to be safe.
        // Actually, simply letting the object go out of scope might be enough, but shutdown is cleaner.
        hidServices.shutdown();
    }

    private static void initializeDevice(HidDevice device) {
        if (!device.isOpen()) {
            boolean openResult = device.open();
            if (!openResult) {
                System.err.println("[JoyConInitializer] Failed to open device: " + device.getProduct());
                return;
            }
        }

        try {
            System.out.println("[JoyConInitializer] initializing " + device.getProduct() + "...");

            // 1. Enable Vibration
            sendSubcommand(device, (byte) 0x48, new byte[]{0x01});
            System.out.println("[JoyConInitializer] Enabled Vibration");

            // 2. Set Input Mode to Standard Full Mode (0x30)
            sendSubcommand(device, (byte) 0x03, new byte[]{0x30});
            System.out.println("[JoyConInitializer] Set Input Mode to Full Mode (0x30)");

            // 3. Enable IMU (keeps connection alive)
            sendSubcommand(device, (byte) 0x40, new byte[]{0x01});
            System.out.println("[JoyConInitializer] Enabled IMU");

            System.out.println("[JoyConInitializer] Initialization complete for " + device.getProduct());

        } catch (Exception e) {
            System.err.println("[JoyConInitializer] Error initializing device: " + e.getMessage());
            e.printStackTrace();
        } finally {
             device.close();
        }
    }

    private static void sendSubcommand(HidDevice device, byte subcommandId, byte[] arguments) {
        byte[] buffer = new byte[49]; // Standard output report size
        
        // Byte 0: Report ID (0x01) - hid4java might handle Report ID via write() method first arg, 
        // but often it's part of the data if numbering is used.
        // The write method signature is: int write(byte[] message, int packetLength, byte reportId)
        
        // Construct the payload (excluding Report ID which is passed separately)
        int offset = 0;
        
        // Byte 1 (in packet structure, but index 0 in data array if reportID passed separately): Global Packet Number
        buffer[offset++] = (byte) (globalPacketCounter & 0x0F);
        globalPacketCounter++;
        if (globalPacketCounter > 0x0F) globalPacketCounter = 0;

        // Byte 2-9 (index 1-8): Rumble Data (Neutral)
        // 00 01 40 40 00 01 40 40
        buffer[offset++] = (byte) 0x00;
        buffer[offset++] = (byte) 0x01;
        buffer[offset++] = (byte) 0x40;
        buffer[offset++] = (byte) 0x40;
        buffer[offset++] = (byte) 0x00;
        buffer[offset++] = (byte) 0x01;
        buffer[offset++] = (byte) 0x40;
        buffer[offset++] = (byte) 0x40;

        // Byte 10 (index 9): Subcommand ID
        buffer[offset++] = subcommandId;

        // Byte 11+ (index 10+): Arguments
        if (arguments != null) {
            for (byte arg : arguments) {
                buffer[offset++] = arg;
            }
        }

        // Send the report
        // Report ID is 0x01
        int res = device.write(buffer, buffer.length, (byte) 0x01);
        
        if (res < 0) {
            System.err.println("[JoyConInitializer] Write failed: " + device.getLastErrorMessage());
        }
        
        // Small delay to ensure controller processes command
        try {
            Thread.sleep(50); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
