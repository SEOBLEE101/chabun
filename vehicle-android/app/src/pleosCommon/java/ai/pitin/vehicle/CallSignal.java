package ai.pitin.vehicle;

import android.media.AudioManager;

/** Whether a call is on, from the audio mode; this needs no phone permission. */
final class CallSignal {
    private CallSignal() { }

    /**
     * A call or a ringing phone shows in the audio mode. Only MODE_NORMAL means "no call"; any other or unknown mode
     * is not taken as "no call".
     */
    static Boolean active(AudioManager audio) {
        if (audio == null) return null;
        int mode = audio.getMode();
        if (mode == AudioManager.MODE_NORMAL) return false;
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING
                || mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT) return true;
        return null;
    }
}
