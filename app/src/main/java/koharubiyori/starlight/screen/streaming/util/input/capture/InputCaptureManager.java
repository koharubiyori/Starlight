package koharubiyori.starlight.screen.streaming.util.input.capture;

import static koharubiyori.starlight.util.UtilKt.canGetRoot;

import android.app.Activity;
import android.view.View;

import koharubiyori.starlight.BuildConfig;
import koharubiyori.starlight.R;
import koharubiyori.starlight.screen.streaming.util.input.evdev.EvdevCaptureProviderShim;
import koharubiyori.starlight.screen.streaming.util.input.evdev.EvdevListener;
import timber.log.Timber;

public class InputCaptureManager {
    public static InputCaptureProvider getInputCaptureProvider(Activity activity, View targetView, EvdevListener rootListener) {
        if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported()) {
            Timber.i("Using Android O+ native mouse capture");
            return new AndroidNativePointerCaptureProvider(activity, targetView);
        }
        // LineageOS implemented broken NVIDIA capture extensions, so avoid using them on root builds.
        // See https://github.com/LineageOS/android_frameworks_base/commit/d304f478a023430f4712dbdc3ee69d9ad02cebd3
        else if (!canGetRoot() && ShieldCaptureProvider.isCaptureProviderSupported()) {
            Timber.i("Using NVIDIA mouse capture extension");
            return new ShieldCaptureProvider(activity);
        }
        else if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            Timber.i("Using Evdev mouse capture");
            return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener);
        }
        else if (AndroidPointerIconCaptureProvider.isCaptureProviderSupported()) {
            // Android N's native capture can't capture over system UI elements
            // so we want to only use it if there's no other option.
            Timber.i("Using Android N+ pointer hiding");
            return new AndroidPointerIconCaptureProvider(activity, targetView);
        }
        else {
            Timber.i("Mouse capture not available");
            return new NullCaptureProvider();
        }
    }
}
