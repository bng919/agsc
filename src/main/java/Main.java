import audio.AudioRecord;
import audio.AudioRecorderFactory;
import data.PassData;
import data.SatelliteData;
import decode.Decoder;
import decode.DecoderFactory;
import instrument.Instrument;
import instrument.InstrumentFactory;
import instrument.Rotator;
import instrument.Transceiver;
import sattrack.SatTrack;
import sattrack.SatTrackFactory;
import utils.Log;
import utils.TLEUtils;
import utils.TimeUtils;
import utils.enums.Verbosity;
import utils.ConfigurationUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String [] args) throws InterruptedException {

        new Log(ConfigurationUtils.getStrProperty("LOG_PATH"), Verbosity.valueOf(ConfigurationUtils.getStrProperty("LOG_LEVEL")));

        Rotator rotator = InstrumentFactory.createRotator(ConfigurationUtils.getStrProperty("ROTATOR_MODEL"));
        Transceiver transceiver = InstrumentFactory.createTransceiver(ConfigurationUtils.getStrProperty("TRANSCEIVER_MODEL"));
        List<Instrument> physicalInstruments = new ArrayList<>();
        physicalInstruments.add(rotator);
        physicalInstruments.add(transceiver);
        for (Instrument instrument : physicalInstruments) {
            if (!instrument.testConnect().isSuccessful() || !instrument.readInstrument().isSuccessful()) {
                throw new RuntimeException("Instrument setup failed for " + instrument);
            }
        }

        AudioRecord audio = AudioRecorderFactory.createAudioRecord(ConfigurationUtils.getStrProperty("RECORDER_MODEL"));
        Decoder dec = DecoderFactory.createDecoder(ConfigurationUtils.getStrProperty("DECODER_MODEL"));
        SatTrack satTrack = SatTrackFactory.createSatTrack(ConfigurationUtils.getStrProperty("SATELLITE_TRACK_MODEL"));

        String[] tle = TLEUtils.fileToStrArray(ConfigurationUtils.getStrProperty("TLE_PATH"));
        //TODO: Satellite shouldn't need currAz, currAl, or visible to be instantiated
        SatelliteData sat = new SatelliteData(tle[0], tle, ConfigurationUtils.getIntProperty("SAT_DL_FREQ_HZ"), 146000000);

        System.out.println(satTrack.getNextTenPasses(sat));

        PassData pass = satTrack.getNextPass(sat);
        List<Double> azProfile = pass.getAzProfile();
        List<Double> elProfile = pass.getElProfile();
        Log.info("Tracking satellite " + sat.getId());
        Log.info("Set to record pass beginning at " + pass.getAos() + ", ending at " + pass.getLos());
        ZonedDateTime setupTime = pass.getAos().minusMinutes(1);
        Log.info("Waiting until AOS minus 1 min to start setup...");
        Log.debug("Waiting for " + setupTime + " before rotator setup");
        while (ZonedDateTime.now(ZoneId.of("UTC")).isBefore(setupTime)) {

        }

        // To rotator initial position
        int initAz = azProfile.getFirst().intValue();
        int initEl = elProfile.getFirst().intValue();
        Log.debug("Moving rotator to initial position Az " + initAz + ", El " + initEl);
        rotator.goToAzEl(initAz, initEl);
        Log.debug("Set transceiver to nominal DL freq " + ConfigurationUtils.getIntProperty("SAT_DL_FREQ_HZ"));
        transceiver.setFrequency(ConfigurationUtils.getIntProperty("SAT_DL_FREQ_HZ"));


        audio.setSampleRate(ConfigurationUtils.getIntProperty("RECORDER_SAMPLE_RATE"));
        audio.setRecordDurationS(pass.getDurationS());

        dec.setDireWolfDir(ConfigurationUtils.getStrProperty("DECODER_PATH"));
        dec.setDurationS(pass.getDurationS());

        Thread audioThread = new Thread(audio);
        Thread decoderThread = new Thread(dec);


        Log.debug("Waiting for AOS at " + pass.getAos());
        while (ZonedDateTime.now(ZoneId.of("UTC")).isBefore(pass.getAos())) {

        }

        audioThread.start();
        decoderThread.start();

        for (int i = 0; i < azProfile.size(); i++) {
            long startTime = System.currentTimeMillis();
            rotator.goToAzEl(azProfile.get(i).intValue(), elProfile.get(i).intValue());
            transceiver.setFrequency(pass.getDlFreqHzAdjProfile().get(i));
            long stopTime = System.currentTimeMillis();
            if (stopTime-startTime < pass.getProfileStepS()*1000L) {
                TimeUtils.delayMillis((pass.getProfileStepS()*1000L) - (stopTime-startTime));
            } else {
                Log.warn("TIME MISALIGNMENT: Setting rotator and transceiver took longer than specified delay.\n" +
                        "Rotator position may lag desired position!");
            }
        }

        try {
            audioThread.join();
            decoderThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        List<byte[]> data = dec.getDecodedData();
        for (byte[] d : data) {
            Log.storeDecodedData(d);
        }

    }
}
