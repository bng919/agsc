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

package integration;

import data.PassData;
import data.SatelliteData;
import instrument.Instrument;
import instrument.InstrumentFactory;
import instrument.Rotator;
import instrument.StubRotatorGS232B;
import org.mockito.Mockito;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import sattrack.SatTrack;
import sattrack.SatTrackPredict4Java;
import utils.Log;
import utils.TimeUtils;
import utils.enums.Verbosity;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class PredictTrackTest {

    SatelliteData sat;
    /*String[] testTle = {"ISS", "1 25544U 98067A   24063.57780891  .00014558  00000-0  26641-3 0  9995",
            "2 25544  51.6409 118.7785 0005862 320.8537 188.7560 15.49575885442173"};*/

    String[] testTle = {"LUCKY7", "1 44406U 19038W   24063.50008472  .00028286  00000-0  81352-3 0  9998",
            "2 44406  97.7030  56.1842 0018091 105.5304 254.7937 15.35597830258108"};


    @BeforeClass
    public void setup() {
        new Log(".\\logs\\", Verbosity.DEBUG);
        sat = Mockito.mock(SatelliteData.class);
        Mockito.when(sat.getTle()).thenReturn(testTle);
        Mockito.when(sat.getNominalDlFreqHz()).thenReturn(140000000L);
    }

    @Test
    public void testPredictTrack() throws InterruptedException {

        SatTrack satTrack = new SatTrackPredict4Java();
        PassData pass = satTrack.getNextPass(sat);
        List<Double> azProfile = pass.getAzProfile();
        List<Double> elProfile = pass.getElProfile();

        Rotator rotator = InstrumentFactory.createRotator("StubRotatorGS232B");

        ZonedDateTime setupTime = pass.getAos().minusMinutes(1);
        Log.debug("Waiting for " + setupTime + " before rotator setup");
        while (ZonedDateTime.now(ZoneId.of("UTC")).isBefore(setupTime)) {

        }

        // To rotator intial position
        int initAz = azProfile.getFirst().intValue();
        int initEl = elProfile.getFirst().intValue();
        Log.debug("Moving rotator to initial position Az " + initAz + ", El " + initEl);
        rotator.goToAzEl(initAz, initEl);

        Log.debug("Waiting for AOS at" + pass.getAos());
        while (ZonedDateTime.now(ZoneId.of("UTC")).isBefore(pass.getAos())) {

        }

        for (int i = 0; i < azProfile.size(); i++) {
            Log.debug("Moving to position Az " + azProfile.get(i).intValue() + ", El " + elProfile.get(i).intValue());
            rotator.goToAzEl(azProfile.get(i).intValue(), elProfile.get(i).intValue());
            TimeUtils.delayMillis(pass.getProfileStepS()*1000L);
        }



    }
}
