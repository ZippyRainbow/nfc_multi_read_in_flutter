package me.ZippyRainbow.nfc_multi_read_in_flutter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

/**
 * NfcMultiReadInFlutterPlugin
 */
public class NfcMultiReadInFlutterPlugin implements FlutterPlugin, ActivityAware {
    private @Nullable FlutterPluginBinding flutterPluginBinding;
    private @Nullable ActivityPluginBinding activityPluginBinding;
    private @Nullable MethodCallHandlerImpl methodCallHandler;

    // This static registerWith() method is no longer needed for V2 plugins
    // It's kept only for compatibility with apps that don't use the v2 Android embedding
    @SuppressWarnings("deprecation")
    public static void registerWith(io.flutter.plugin.common.PluginRegistry.Registrar registrar) {
        MethodCallHandlerImpl handler = new MethodCallHandlerImpl(registrar.activity(), registrar.messenger());
        registrar.addNewIntentListener(handler);
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
        this.activityPluginBinding = binding;
        if (flutterPluginBinding != null) {
            methodCallHandler = new MethodCallHandlerImpl(
                    binding.getActivity(),
                    flutterPluginBinding.getBinaryMessenger()
            );
            binding.addOnNewIntentListener(methodCallHandler);
        }
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
        if (methodCallHandler != null && activityPluginBinding != null) {
            activityPluginBinding.removeOnNewIntentListener(methodCallHandler);
            methodCallHandler.stopListening();
            methodCallHandler = null;
        }
        activityPluginBinding = null;
    }
}