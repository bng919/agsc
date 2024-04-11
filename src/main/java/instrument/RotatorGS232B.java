/*
 * Copyright (C) 2024  Benjamin Graham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package instrument;

import utils.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Class for communication with the Yaesu GS232B rotator controller.
 */
public class RotatorGS232B implements Rotator {

    private static final int AZ_TOLERANCE_DEG = 2;
    private static final int EL_TOLERANCE_DEG = 2;
    private final String correctionFilePath;
    private final int[] correctionList = new int[360];
    private static final int MOTION_TIMEOUT_MILLIS = 40000; //TODO: Scale based on delta to move
    SerialUtils serialUtils;
    private final String comPort;
    private final int baudRate;
    private int currAz;
    private int currEl;

    /**
     * Instate this class via the {@link InstrumentFactory} only.
     * @throws InterruptedException
     */
    protected RotatorGS232B() throws InterruptedException {
        this.comPort = ConfigurationUtils.getStrProperty("ROTATOR_COM_PORT");
        this.baudRate = ConfigurationUtils.getIntProperty("ROTATOR_BAUD");
        this.correctionFilePath = ConfigurationUtils.getStrProperty("ROTATOR_CALIBRATION_PATH");
        readCorrectionFile();
        this.serialUtils = new SerialUtils(this.comPort, this.baudRate, 8, 1, 0);
    }

    /**
     * Read the values of the rotator correction configuration file.
     */
    private void readCorrectionFile() {
        BufferedReader fileReader;
        try {
            fileReader = new BufferedReader(new FileReader(correctionFilePath));
            String line = fileReader.readLine();
            int count = 0;
            while (line != null) {
                if (count > 359) {
                    throw new RuntimeException("Too many lines in rotator calibration file at " + this.correctionFilePath);
                }
                correctionList[count] = Integer.parseInt(line.strip());
                line = fileReader.readLine();
                count++;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ResultUtils readInstrument() throws InterruptedException {
        byte[] readAzElCmd = {0x43, 0x32, 0x0D};

        this.serialUtils.open();
        this.serialUtils.write(readAzElCmd);
        TimeUnit.MILLISECONDS.sleep(250);
        byte[] rst = this.serialUtils.read();
        if (rst.length == 0) {
            return ResultUtils.createFailedResult();
        }
        this.serialUtils.close();

        int azOffset = 3;
        int elOffset = 11;
        if(rst[3] == '-') { //Az can read as -000, disregard minus sign
            azOffset++;
            elOffset++;
        }

        int azAngle = 0;
        int elAngle = 0;

        try { //TODO: REMOVE TRY CATCH
            //TODO: Cleanup to loop
            azAngle += (rst[0 + azOffset] - 48) * 100;
            elAngle += (rst[0 + elOffset] - 48) * 100;
            azAngle += (rst[1 + azOffset] - 48) * 10;
            elAngle += (rst[1 + elOffset] - 48) * 10;
            azAngle += (rst[2 + azOffset] - 48);
            elAngle += (rst[2 + elOffset] - 48);
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.error(e.getMessage());
            Log.error(HexadecimalUtils.hexDump(rst));
            return ResultUtils.createFailedResult();
        }

        this.currAz = azAngle;
        this.currEl = elAngle;

        return ResultUtils.createSuccessfulResult();
    }

    public ResultUtils testConnect() throws InterruptedException {
        if (serialUtils.open() && readInstrument().isSuccessful() && serialUtils.close()) {
            return ResultUtils.createSuccessfulResult();
        }
        else {
            Log.error("RotatorGS232B connection test failed! Could not connect using port " + this.comPort
                    + " with baud " + this.baudRate);
            return ResultUtils.createFailedResult();
        }
    }

    public int getAz() {
        return currAz;
    }

    public int getEl() {
        return currEl;
    }

    public ResultUtils goToAz(int az) throws InterruptedException {
        if (az < 0 || az > 359) {
            return ResultUtils.createFailedResult();
        }
        az = correctionList[az];
        Log.debug("After correcting for calibration, az = " + az);
        byte[] setAzCmd = "M".getBytes();
        byte[] posByte = String.valueOf(az).getBytes();
        byte[] cmd = {setAzCmd[0], 0x30, 0x30, 0x30, 0x0D};
        for (int i = 0; i < posByte.length; i++) {
            cmd[cmd.length-2-i] = posByte[posByte.length-1-i];
        }

        this.serialUtils.open();
        this.serialUtils.write(cmd);
        TimeUnit.MILLISECONDS.sleep(200);
        this.serialUtils.close();

        long motionStart = System.currentTimeMillis();
        //readInstrument();
        while (this.currAz <= az-AZ_TOLERANCE_DEG || this.currAz >= az+AZ_TOLERANCE_DEG) {
            if ((System.currentTimeMillis()-motionStart) >= MOTION_TIMEOUT_MILLIS) {
                return ResultUtils.createFailedResult();
            }
            TimeUnit.MILLISECONDS.sleep(200);
            readInstrument();
        }
        return ResultUtils.createSuccessfulResult();
    }

    public ResultUtils goToEl(int el) throws InterruptedException {
        if (el < 0 || el > 180) {
            return ResultUtils.createFailedResult();
        }
        readInstrument();
        byte[] posByte = String.valueOf(el).getBytes();
        byte[] currAzByte = String.valueOf(this.currAz).getBytes();
        byte[] cmd = {0x57, 0x30, 0x30, 0x30, 0x20, 0x30, 0x30, 0x30, 0x0D};
        for (int i = 0; i < posByte.length; i++) {
            cmd[cmd.length-2-i] = posByte[posByte.length-1-i];
        }
        for (int i = 0; i < currAzByte.length; i++) {
            cmd[cmd.length-6-i] = currAzByte[currAzByte.length-1-i];
        }
        this.serialUtils.open();
        this.serialUtils.write(cmd);
        TimeUnit.MILLISECONDS.sleep(200);
        this.serialUtils.close();

        long motionStart = System.currentTimeMillis();
        while (this.currEl <= el-EL_TOLERANCE_DEG || this.currEl >= el+EL_TOLERANCE_DEG) {
            if ((System.currentTimeMillis()-motionStart) >= MOTION_TIMEOUT_MILLIS) {
                return ResultUtils.createFailedResult();
            }
            TimeUnit.MILLISECONDS.sleep(200);
            readInstrument();
        }
        return ResultUtils.createSuccessfulResult();
    }

    public ResultUtils goToAzEl(int az, int el) throws InterruptedException {
        // Assume successful
        ResultUtils azRst = ResultUtils.createSuccessfulResult();
        ResultUtils elRst = ResultUtils.createSuccessfulResult();
        if (Math.abs(this.currAz - az) > AZ_TOLERANCE_DEG) {
            Log.info("Moving to position Az " + az);
            azRst = goToAz(az);
        } else {
            Log.debug("Az not updated on instrument, new Az less then " + AZ_TOLERANCE_DEG + " from current position");
        }
        if (Math.abs(this.currEl - el) > EL_TOLERANCE_DEG) {
            Log.info("Moving to position El " + el);
            elRst = goToEl(el);
        } else {
            Log.debug("El not updated on instrument, new El less then " + EL_TOLERANCE_DEG + " from current position");
        }
        return azRst.and(elRst);
    }
}
