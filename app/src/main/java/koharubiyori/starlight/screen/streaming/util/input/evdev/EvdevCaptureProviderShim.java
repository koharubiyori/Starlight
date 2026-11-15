package koharubiyori.starlight.screen.streaming.util.input.evdev;


import static koharubiyori.starlight.util.UtilKt.canGetRoot;

import android.app.Activity;

import koharubiyori.starlight.screen.streaming.util.input.capture.InputCaptureProvider;

public class EvdevCaptureProviderShim {
    public static boolean isCaptureProviderSupported() {
        return canGetRoot();
    }

    // We need to construct our capture provider using reflection because it isn't included in non-root builds
    public static InputCaptureProvider createEvdevCaptureProvider(Activity activity, EvdevListener listener) {
        try {
            Class providerClass = Class.forName("koharubiyori.starlight.screen.streaming.util.input.evdev.EvdevCaptureProvider");
            return (InputCaptureProvider) providerClass.getConstructors()[0].newInstance(activity, listener);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
