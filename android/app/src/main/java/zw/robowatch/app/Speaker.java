package zw.robowatch.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;

/** Speaks alerts and plays the warning beep. Everything runs on the main thread. */
final class Speaker implements TextToSpeech.OnInitListener {
    private final TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> pending = new ArrayList<>();
    private ToneGenerator tone;
    private boolean ready = false;
    private int seq = 0;

    Speaker(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        } catch (RuntimeException e) {
            tone = null;
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
        } catch (Exception ignored) {
        }
        ready = true;
        for (String text : pending) speakNow(text);
        pending.clear();
    }

    /** Queue a sentence; sentences are spoken one after another, never cut off. */
    void say(final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (ready) speakNow(text);
                else if (pending.size() < 5) pending.add(text);
            }
        });
    }

    private void speakNow(String text) {
        try {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "rw" + (seq++));
        } catch (Exception ignored) {
        }
    }

    void beep(int times) {
        for (int i = 0; i < times; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
                    } catch (Exception ignored) {
                    }
                }
            }, i * 300L);
        }
    }

    void shutdown() {
        handler.removeCallbacksAndMessages(null);
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
        try {
            if (tone != null) tone.release();
        } catch (Exception ignored) {
        }
        tone = null;
    }
}
