package vnes.emulator;
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

import vnes.Tile;
import vnes.emulator.mappers.MapperDefault;
import vnes.emulator.mappers.MemoryMapper;
import vnes.utils.FileLoader;

import java.io.*;
import java.util.function.Consumer;

public class ROM {

    // Mirroring types:
    public static final int VERTICAL_MIRRORING = 0;
    public static final int HORIZONTAL_MIRRORING = 1;
    public static final int FOURSCREEN_MIRRORING = 2;
    public static final int SINGLESCREEN_MIRRORING = 3;
    public static final int SINGLESCREEN_MIRRORING2 = 4;
    public static final int SINGLESCREEN_MIRRORING3 = 5;
    public static final int SINGLESCREEN_MIRRORING4 = 6;
    public static final int CHRROM_MIRRORING = 7;
    boolean failedSaveFile = false;
    boolean saveRamUpToDate = true;
    short[] header;
    short[][] rom;
    short[][] vrom;
    short[] saveRam;
    Tile[][] vromTile;
    private final Consumer<Integer> showLoadProgress;
    private final Consumer<String> showErrorMsg;
    int romCount;
    int vromCount;
    int mirroring;
    boolean batteryRam;
    boolean trainer;
    boolean fourScreen;
    int mapperType;
    String fileName;
    RandomAccessFile raFile;
    boolean enableSave = true;
    boolean valid;
    static String[] mapperName;
    static boolean[] mapperSupported;

    static {

        mapperName = new String[255];
        mapperSupported = new boolean[255];
        for (int i = 0; i < 255; i++) {
            mapperName[i] = "Unknown Mapper";
        }

        mapperName[0] = "NROM";

        // The mappers supported:
        mapperSupported[ 0] = true; // No Mapper
    }

    public ROM(Consumer<Integer> showLoadProgress, Consumer<String> showErrorMsg) {
        this.showLoadProgress = showLoadProgress;
        this.showErrorMsg = showErrorMsg;
        valid = false;
    }

    public void load(String fileName) {

        this.fileName = fileName;
        System.out.println(fileName);
        FileLoader loader = new FileLoader();
        short[] b = loader.loadFile(fileName, showLoadProgress);

        if (b == null || b.length == 0) {

            // Unable to load file.
            showErrorMsg.accept("Unable to load ROM file.");
            valid = false;

        }

        // Read header:
        header = new short[16];
        for (int i = 0; i < 16; i++) {
            header[i] = b[i];
        }

        // Check first four bytes:
        String fcode = new String(new byte[]{(byte) b[0], (byte) b[1], (byte) b[2], (byte) b[3]});
        if (!fcode.equals("NES" + new String(new byte[]{0x1A}))) {
            //System.out.println("Header is incorrect.");
            valid = false;
            return;
        }

        // Read header:
        romCount = header[4];
        vromCount = header[5] * 2; // Get the number of 4kB banks, not 8kB
        mirroring = ((header[6] & 1) != 0 ? 1 : 0);
        batteryRam = (header[6] & 2) != 0;
        trainer = (header[6] & 4) != 0;
        fourScreen = (header[6] & 8) != 0;
        mapperType = (header[6] >> 4) | (header[7] & 0xF0);

        // Battery RAM?
//        if (batteryRam) {
//            loadBatteryRam();
//        }

        // Check whether byte 8-15 are zero's:
        boolean foundError = false;
        for (int i = 8; i < 16; i++) {
            if (header[i] != 0) {
                foundError = true;
                break;
            }
        }
        if (foundError) {
            // Ignore byte 7.
            mapperType &= 0xF;
        }

        rom = new short[romCount][16384];
        vrom = new short[vromCount][4096];
        vromTile = new Tile[vromCount][256];

        //try{

        // Load PRG-ROM banks:
        int offset = 16;
        for (int i = 0; i < romCount; i++) {
            for (int j = 0; j < 16384; j++) {
                if (offset + j >= b.length) {
                    break;
                }
                rom[i][j] = b[offset + j];
            }
            offset += 16384;
        }

        // Load CHR-ROM banks:
        for (int i = 0; i < vromCount; i++) {
            for (int j = 0; j < 4096; j++) {
                if (offset + j >= b.length) {
                    break;
                }
                vrom[i][j] = b[offset + j];
            }
            offset += 4096;
        }

        // Create VROM tiles:
        for (int i = 0; i < vromCount; i++) {
            for (int j = 0; j < 256; j++) {
                vromTile[i][j] = new Tile();
            }
        }

        // Convert CHR-ROM banks to tiles:
        //System.out.println("Converting CHR-ROM image data..");
        //System.out.println("VROM bank count: "+vromCount);
        int tileIndex;
        int leftOver;
        for (int v = 0; v < vromCount; v++) {
            for (int i = 0; i < 4096; i++) {
                tileIndex = i >> 4;
                leftOver = i % 16;
                if (leftOver < 8) {
                    vromTile[v][tileIndex].setScanline(leftOver, vrom[v][i], vrom[v][i + 8]);
                } else {
                    vromTile[v][tileIndex].setScanline(leftOver - 8, vrom[v][i - 8], vrom[v][i]);
                }
            }
        }

        valid = true;

    }

    public boolean isValid() {
        return valid;
    }

    public int getRomBankCount() {
        return romCount;
    }

    // Returns number of 4kB VROM banks.
    public int getVromBankCount() {
        return vromCount;
    }

    public short[] getHeader() {
        return header;
    }

    public short[] getRomBank(int bank) {
        return rom[bank];
    }

    public short[] getVromBank(int bank) {
        return vrom[bank];
    }

    public Tile[] getVromBankTiles(int bank) {
        return vromTile[bank];
    }

    public int getMirroringType() {

        if (fourScreen) {
            return FOURSCREEN_MIRRORING;
        }

        if (mirroring == 0) {
            return HORIZONTAL_MIRRORING;
        }

        // default:
        return VERTICAL_MIRRORING;

    }

    public int getMapperType() {
        return mapperType;
    }

    public String getMapperName() {

        if (mapperType >= 0 && mapperType < mapperName.length) {
            return mapperName[mapperType];
        }
        // else:
        return "Unknown Mapper, " + mapperType;

    }

    public boolean hasBatteryRam() {
        return batteryRam;
    }

    public boolean hasTrainer() {
        return trainer;
    }

    public String getFileName() {
        File f = new File(fileName);
        return f.getName();
    }

    public boolean mapperSupported() {
        if (mapperType < mapperSupported.length && mapperType >= 0) {
            return mapperSupported[mapperType];
        }
        return false;
    }

    public MemoryMapper createMapper() {

        if (mapperSupported()) {
            switch (mapperType) {
                case 0: {
                    return new MapperDefault();
                }
            }
        }

        // If the mapper wasn't supported, create the standard one:
        showErrorMsg.accept("Warning: Mapper not supported yet.");
        return new MapperDefault();

    }

    public void setSaveState(boolean enableSave) {
        //this.enableSave = enableSave;
        if (enableSave && !batteryRam) {
//          loadBatteryRam();
        }
    }

    public short[] getBatteryRam() {

        return saveRam;

    }

    public void destroy() {

    }
}
