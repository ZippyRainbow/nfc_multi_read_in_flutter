package me.ZippyRainbow.nfc_multi_read_in_flutter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

public class NfcMultiReadInFlutterPlugin implements FlutterPlugin, ActivityAware {
    private @Nullable FlutterPluginBinding flutterPluginBinding;
    private @Nullable ActivityPluginBinding activityPluginBinding;
    private @Nullable MethodCallHandlerImpl methodCallHandler;

    // Remove the deprecated registerWith() method completely
    // No need for backward compatibility if you're targeting Flutter 3.29+

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