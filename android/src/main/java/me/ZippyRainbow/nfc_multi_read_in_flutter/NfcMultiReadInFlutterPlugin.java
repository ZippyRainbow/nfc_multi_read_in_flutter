package me.ZippyRainbow.nfc_multi_read_in_flutter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

/**
 * NfcMultiReadInFlutterPlugin
 */
public class NfcMultiReadInFlutterPlugin implements FlutterPlugin,
        ActivityAware{

    private @Nullable FlutterPluginBinding flutterPluginBinding;
    private static @Nullable MethodCallHandlerImpl methodCallHandler;

    /**
     * Plugin registration.
     */
    @SuppressWarnings("deprecation")
    public static void registerWith(Registrar registrar) {
        methodCallHandler = new MethodCallHandlerImpl(registrar.activity(), registrar.messenger());
        registrar.addNewIntentListener(methodCallHandler);
    }

    public NfcMultiReadInFlutterPlugin() {
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        this.flutterPluginBinding = binding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        this.flutterPluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        methodCallHandler = new MethodCallHandlerImpl(binding.getActivity(), flutterPluginBinding.getBinaryMessenger());
        binding.addOnNewIntentListener(methodCallHandler);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        // Could be on too low of an SDK to have started listening originally.
        if (methodCallHandler != null) {
            methodCallHandler.stopListening();
            methodCallHandler = null;
          }
    }

}