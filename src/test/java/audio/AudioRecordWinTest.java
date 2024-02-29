package audio;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import utils.Log;
import utils.ResultHelper;
import utils.enums.Verbosity;

import static org.testng.Assert.*;

public class AudioRecordWinTest {

    @BeforeClass
    public void setup() {
        new Log(".\\logs\\", Verbosity.DEBUG);
    }

    @Test
    public void testStartRecording() {
        int recordDuration = 10;
        AudioRecordWin a = new AudioRecordWin(recordDuration,44100);
        long startTime = java.time.Instant.now().getEpochSecond();
        ResultHelper rst = a.startRecording();
        long stopTime = java.time.Instant.now().getEpochSecond();
        // Check if actual record time matches requested duration with tolerance to account for overhead
        boolean timeEqual = (stopTime-startTime) >= recordDuration && (stopTime-startTime) <= recordDuration+1;

        assertTrue(rst.isSuccessful());
        assertTrue(timeEqual);
    }
}