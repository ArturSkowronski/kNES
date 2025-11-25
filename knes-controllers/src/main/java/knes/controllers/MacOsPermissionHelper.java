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

package knes.controllers;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

public class MacOsPermissionHelper {

    // Define the IOKit library interface
    public interface IOKit extends Library {
        IOKit INSTANCE = Native.load("IOKit", IOKit.class);

        // Constants
        int kIOHIDRequestTypeListenEvent = 1;
        int kIOHIDAccessTypeGranted = 0;
        int kIOHIDAccessTypeDenied = 1;
        int kIOHIDAccessTypeUnknown = 2;

        // Function definitions
        int IOHIDCheckAccess(int requestType);
        boolean IOHIDRequestAccess(int requestType);
    }

    public static boolean checkAndRequestInputMonitoring() {
        if (!Platform.isMac()) {
            return true; // Not macOS, so permission is "granted"
        }

        try {
            // Check current access status
            int status = IOKit.INSTANCE.IOHIDCheckAccess(IOKit.kIOHIDRequestTypeListenEvent);

            if (status == IOKit.kIOHIDAccessTypeGranted) {
                System.out.println("[MacOsPermissionHelper] Input Monitoring permission already granted.");
                return true;
            }

            System.out.println("[MacOsPermissionHelper] Input Monitoring permission status: " + status + ". Requesting access...");
            
            // Request access (this should trigger the system popup if not already denied explicitly)
            boolean result = IOKit.INSTANCE.IOHIDRequestAccess(IOKit.kIOHIDRequestTypeListenEvent);
            
            if (result) {
                 System.out.println("[MacOsPermissionHelper] Access request initiated. Please check system dialogs.");
            } else {
                 System.err.println("[MacOsPermissionHelper] Failed to initiate access request.");
            }
            
            return false;

        } catch (Throwable e) {
            // Fallback in case IOKit functions are not available (e.g. older macOS versions) or JNA issues
            System.err.println("[MacOsPermissionHelper] Error checking permissions: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
