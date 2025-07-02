package me.ZippyRainbow.nfc_multi_read_in_flutter;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;

final class MethodCallHandlerImpl implements
        MethodCallHandler,
        EventChannel.StreamHandler,
        PluginRegistry.NewIntentListener,
        NfcAdapter.ReaderCallback,
        NfcAdapter.OnTagRemovedListener {

    private static final String NORMAL_READER_MODE = "normal";
    private static final String DISPATCH_READER_MODE = "dispatch";
    private static final int ONE_SECOND = 1000;
    private final int DEFAULT_READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V;
    private static final String LOG_TAG = "NfcMultiReadInFlutterPlugin";

    private final Activity activity;
    private @Nullable NfcAdapter adapter;
    private @Nullable EventChannel.EventSink events;
    private final MethodChannel channel;
    private final EventChannel tagChannel;

    private @Nullable String currentReaderMode = null;
    private @Nullable Tag lastTag = null;
    private boolean writeIgnore = false;

    MethodCallHandlerImpl(@NonNull Activity activity, @NonNull BinaryMessenger messenger) {
        this.activity = activity;
        this.adapter = NfcAdapter.getDefaultAdapter(activity);

        channel = new MethodChannel(messenger, "nfc_multi_read_in_flutter");
        tagChannel = new EventChannel(messenger, "nfc_multi_read_in_flutter/tags");
        channel.setMethodCallHandler(this);
        tagChannel.setStreamHandler(this);
    }

    void stopListening() {
        channel.setMethodCallHandler(null);
        tagChannel.setStreamHandler(null);
        disableNfcOperations();
    }

    private void disableNfcOperations() {
        if (adapter != null && currentReaderMode != null) {
            switch (currentReaderMode) {
                case NORMAL_READER_MODE:
                    adapter.disableReaderMode(activity);
                    break;
                case DISPATCH_READER_MODE:
                    adapter.disableForegroundDispatch(activity);
                    break;
                default:
                    Log.e(LOG_TAG, "Unknown reader mode: " + currentReaderMode);
            }
        }
        currentReaderMode = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        try {
            switch (call.method) {
                case "readNDEFSupported":
                    result.success(nfcIsSupported());
                    break;
                case "readNDEFEnabled":
                    result.success(nfcIsEnabled());
                    break;
                case "stopNDEFReading":
                    result.success(stopReading());
                    break;
                case "startNDEFReading":
                    handleStartNdefReading(call, result);
                    break;
                case "writeNDEF":
                    handleWriteNdef(call, result);
                    break;
                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in method " + call.method, e);
            result.error("UNKNOWN_ERROR", e.getMessage(), null);
        }
    }

    private void handleStartNdefReading(MethodCall call, Result result) {
        if (!(call.arguments instanceof Map)) {
            result.error("INVALID_ARGUMENTS", "startNDEFReading requires Map arguments", null);
            return;
        }

        Map<?, ?> args = (Map<?, ?>) call.arguments;
        Boolean readForWrite = (Boolean) args.get("read_for_write");
        writeIgnore = readForWrite != null && readForWrite;

        String readerMode = (String) args.get("reader_mode");
        if (readerMode == null) {
            result.error("MISSING_READER_MODE", "reader_mode is required", null);
            return;
        }

        if (currentReaderMode != null && !readerMode.equals(currentReaderMode)) {
            result.error("MULTIPLE_READER_MODES", "Cannot change reader mode while active", null);
            return;
        }

        currentReaderMode = readerMode;
        switch (readerMode) {
            case NORMAL_READER_MODE:
                boolean noSounds = args.get("no_platform_sounds") == Boolean.TRUE;
                startReading(noSounds);
                break;
            case DISPATCH_READER_MODE:
                startReadingWithForegroundDispatch();
                break;
            default:
                result.error("UNKNOWN_READER_MODE", "Unknown reader mode: " + readerMode, null);
                return;
        }
        result.success(null);
    }

    private void handleWriteNdef(MethodCall call, Result result) {
        if (!(call.arguments instanceof Map)) {
            result.error("INVALID_ARGUMENTS", "writeNDEF requires Map arguments", null);
            return;
        }

        Map<?, ?> writeArgs = (Map<?, ?>) call.arguments;
        Map<?, ?> messageMap = (Map<?, ?>) writeArgs.get("message");
        if (messageMap == null) {
            result.error("MISSING_MESSAGE", "NDEF message is required", null);
            return;
        }

        try {
            NdefMessage message = formatMapToNdefMessage(messageMap);
            writeNDEF(message);
            result.success(null);
        } catch (NfcMultiReadInFlutterException e) {
            result.error(e.code, e.message, e.details);
        } catch (Exception e) {
            result.error("UNKNOWN_ERROR", e.getMessage(), null);
        }
    }

    private Boolean nfcIsEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    private Boolean nfcIsSupported() {
        return adapter != null;
    }

    private Boolean stopReading() {
        disableNfcOperations();
        return true;
    }

    private void startReading(boolean noSounds) {
        if (adapter == null) return;

        int flags = DEFAULT_READER_FLAGS;
        if (noSounds) {
            flags |= NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
        }
        adapter.enableReaderMode(activity, this, flags, null);
    }

    private void startReadingWithForegroundDispatch() {
        if (adapter == null) return;

        Intent intent = new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                activity,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE
        );

        adapter.enableForegroundDispatch(
                activity,
                pendingIntent,
                null,
                null
        );
    }

    @Override
    public void onListen(Object args, EventChannel.EventSink eventSink) {
        this.events = eventSink;
    }

    @Override
    public void onCancel(Object args) {
        disableNfcOperations();
        this.events = null;
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        lastTag = tag;
        Ndef ndef = Ndef.get(tag);
        NdefFormatable formatable = NdefFormatable.get(tag);

        if (ndef != null) {
            handleNdefTag(ndef);
        } else if (formatable != null) {
            eventSuccess(formatEmptyWritableNdefMessage());
        }
    }

    private void handleNdefTag(Ndef ndef) {
        boolean closed = false;
        try {
            ndef.connect();
            NdefMessage message = ndef.getNdefMessage();
            if (message == null) {
                eventSuccess(formatEmptyNdefMessage(ndef));
                return;
            }

            ndef.close();
            closed = true;

            eventSuccess(formatNdefMessageToResult(ndef, message));
            ignoreTagIfNeeded(ndef.getTag());
        } catch (IOException e) {
            Map<String, Object> details = new HashMap<>();
            details.put("fatal", true);
            eventError("IOError", e.getMessage(), details);
        } catch (FormatException e) {
            eventError("NDEFBadFormatError", e.getMessage(), null);
        } finally {
            if (!closed) {
                try {
                    ndef.close();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Error closing NDEF tag", e);
                }
            }
        }
    }

    private void ignoreTagIfNeeded(Tag tag) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                !writeIgnore &&
                adapter != null) {
            adapter.ignore(tag, ONE_SECOND, this, null);
        }
    }

    // ... [rest of the methods like formatNdefMessageToResult, formatMapToNdefMessage, etc.]
    // These can remain largely the same, just add @Nullable and @NonNull annotations where appropriate
    // and improve null safety checks

    private void eventSuccess(final Object result) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (events != null) {
                events.success(result);
            }
        });
    }

    private void eventError(final String code, final String message, final Object details) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (events != null) {
                events.error(code, message, details);
            }
        });
    }
}