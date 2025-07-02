package me.ZippyRainbow.nfc_multi_read_in_flutter;

import android.nfc.NfcAdapter;
import android.util.Log;
import android.app.PendingIntent;
import java.io.IOException;
import android.nfc.FormatException;

import java.math.BigInteger;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MethodCallHandlerImpl implements
        MethodChannel.MethodCallHandler,
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



    @Override
    public boolean onNewIntent(Intent intent) {
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                lastTag = tag;
                handleNDEFTagFromIntent(tag);
                return true;
            }
        }
        return false;
    }

    private String getNDEFTagID(Ndef ndef) {
        byte[] idByteArray = ndef.getTag().getId();
        return String.format("%0" + (idByteArray.length * 2) + "X", new BigInteger(1, idByteArray));
    }

    private void handleNDEFTagFromIntent(Tag tag) {
        Ndef ndef = Ndef.get(tag);
        NdefFormatable formatable = NdefFormatable.get(tag);

        Map<String, Object> result;
        if (ndef != null) {
            NdefMessage message = ndef.getCachedNdefMessage();
            try {
                ndef.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "close NDEF tag error: " + e.getMessage());
            }
            result = formatNdefMessageToResult(ndef, message);
        } else if (formatable != null) {
            result = formatEmptyWritableNdefMessage();
        } else {
            return;
        }
        eventSuccess(result);
    }

    private Map<String, Object> formatEmptyWritableNdefMessage() {
        final Map<String, Object> result = new HashMap<>();
        result.put("id", "");
        result.put("message_type", "ndef");
        result.put("type", "");
        result.put("writable", true);
        List<Map<String, String>> records = new ArrayList<>();
        Map<String, String> emptyRecord = new HashMap<>();
        emptyRecord.put("tnf", "empty");
        emptyRecord.put("id", "");
        emptyRecord.put("type", "");
        emptyRecord.put("payload", "");
        emptyRecord.put("data", "");
        emptyRecord.put("languageCode", "");
        records.add(emptyRecord);
        result.put("records", records);
        return result;
    }

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

    private Map<String, Object> formatEmptyNdefMessage(Ndef ndef) {
        final Map<String, Object> result = formatEmptyWritableNdefMessage();
        result.put("id", getNDEFTagID(ndef));
        result.put("writable", ndef.isWritable());
        return result;
    }

    private Map<String, Object> formatNdefMessageToResult(Ndef ndef, NdefMessage message) {
        final Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();

        for (NdefRecord record : message.getRecords()) {
            Map<String, Object> recordMap = new HashMap<>();
            byte[] recordPayload = record.getPayload();
            Charset charset = StandardCharsets.UTF_8;
            short tnf = record.getTnf();
            byte[] type = record.getType();

            // Handle different record types
            if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                charset = ((recordPayload[0] & 128) == 0) ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16;
                int languageCodeLength = recordPayload[0] & 0x3F;
                recordMap.put("languageCode", new String(recordPayload, 1, languageCodeLength, StandardCharsets.US_ASCII));
                recordMap.put("payload", new String(recordPayload, languageCodeLength + 1, recordPayload.length - languageCodeLength - 1, charset));
            } else if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
                recordMap.put("payload", new String(recordPayload, 1, recordPayload.length - 1, charset));
            } else {
                recordMap.put("payload", new String(recordPayload, charset));
            }

            recordMap.put("tnf", getTnfString(tnf));
            recordMap.put("type", new String(type, StandardCharsets.US_ASCII));
            recordMap.put("id", new String(record.getId(), StandardCharsets.US_ASCII));
            records.add(recordMap);
        }

        result.put("id", getNDEFTagID(ndef));
        result.put("message_type", "ndef");
        result.put("type", ndef.getType());
        result.put("records", records);
        result.put("writable", ndef.isWritable());
        return result;
    }

    private String getTnfString(short tnf) {
        switch (tnf) {
            case NdefRecord.TNF_EMPTY: return "empty";
            case NdefRecord.TNF_WELL_KNOWN: return "well_known";
            case NdefRecord.TNF_MIME_MEDIA: return "mime_media";
            case NdefRecord.TNF_ABSOLUTE_URI: return "absolute_uri";
            case NdefRecord.TNF_EXTERNAL_TYPE: return "external_type";
            case NdefRecord.TNF_UNKNOWN: return "unknown";
            default: return "unknown";
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

    private NdefMessage formatMapToNdefMessage(Map<?, ?> map) throws NfcMultiReadInFlutterException {
        Object mapRecordsObj = map.get("records");
        if (mapRecordsObj == null) {
            throw new NfcMultiReadInFlutterException("MISSING_RECORDS", "NDEF message must contain records", null);
        }

        if (!(mapRecordsObj instanceof List)) {
            throw new NfcMultiReadInFlutterException("INVALID_RECORDS", "Records must be a list", null);
        }

        List<?> mapRecords = (List<?>) mapRecordsObj;
        NdefRecord[] records = new NdefRecord[mapRecords.size()];

        for (int i = 0; i < mapRecords.size(); i++) {
            Object recordObj = mapRecords.get(i);
            if (!(recordObj instanceof Map)) {
                throw new NfcMultiReadInFlutterException("INVALID_RECORD", "Each record must be a map", null);
            }

            Map<?, ?> recordMap = (Map<?, ?>) recordObj;
            records[i] = createNdefRecordFromMap(recordMap);
        }

        return new NdefMessage(records);
    }

    private NdefRecord createNdefRecordFromMap(Map<?, ?> recordMap) throws NfcMultiReadInFlutterException {
        try {
            // Get required fields
            String tnfStr = (String) recordMap.get("tnf");
            String type = (String) recordMap.getOrDefault("type", "");
            String id = (String) recordMap.getOrDefault("id", "");
            String payload = (String) recordMap.getOrDefault("payload", "");

            // Convert TNF string to short value
            short tnf = convertTnfStringToShort(tnfStr);

            // Handle special cases for well-known types
            if (tnf == NdefRecord.TNF_WELL_KNOWN) {
                byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
                if (Arrays.equals(typeBytes, NdefRecord.RTD_TEXT)) {
                    String languageCode = (String) recordMap.getOrDefault("languageCode", Locale.getDefault().getLanguage());
                    return createTextRecord(payload, languageCode);
                } else if (Arrays.equals(typeBytes, NdefRecord.RTD_URI)) {
                    return NdefRecord.createUri(payload);
                }
            }

            // Create standard record
            return new NdefRecord(
                    tnf,
                    type.getBytes(StandardCharsets.US_ASCII),
                    id.getBytes(StandardCharsets.US_ASCII),
                    payload.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            throw new NfcMultiReadInFlutterException("RECORD_CREATION_ERROR", e.getMessage(), null);
        }
    }

    private short convertTnfStringToShort(String tnfStr) throws NfcMultiReadInFlutterException {
        if (tnfStr == null) {
            throw new NfcMultiReadInFlutterException("MISSING_TNF", "Record must have TNF value", null);
        }

        switch (tnfStr.toLowerCase()) {
            case "empty": return NdefRecord.TNF_EMPTY;
            case "well_known": return NdefRecord.TNF_WELL_KNOWN;
            case "mime_media": return NdefRecord.TNF_MIME_MEDIA;
            case "absolute_uri": return NdefRecord.TNF_ABSOLUTE_URI;
            case "external_type": return NdefRecord.TNF_EXTERNAL_TYPE;
            case "unknown": return NdefRecord.TNF_UNKNOWN;
            default:
                throw new NfcMultiReadInFlutterException("INVALID_TNF", "Unknown TNF value: " + tnfStr, null);
        }
    }

    private NdefRecord createTextRecord(String text, String languageCode) {
        byte[] langBytes = languageCode.getBytes(StandardCharsets.US_ASCII);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        byte[] payload = new byte[1 + langBytes.length + textBytes.length];
        payload[0] = (byte) langBytes.length; // status byte
        System.arraycopy(langBytes, 0, payload, 1, langBytes.length);
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.length, textBytes.length);

        return new NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_TEXT,
                new byte[0], // id
                payload
        );
    }

    private void writeNDEF(NdefMessage message) throws NfcMultiReadInFlutterException {
        if (lastTag == null) {
            throw new NfcMultiReadInFlutterException("NO_TAG", "No tag available for writing", null);
        }

        Ndef ndef = Ndef.get(lastTag);
        NdefFormatable formatable = NdefFormatable.get(lastTag);

        try {
            if (ndef != null) {
                handleNdefTagWrite(ndef, message);
            } else if (formatable != null) {
                handleNdefFormatableTagWrite(formatable, message);
            } else {
                throw new NfcMultiReadInFlutterException("UNSUPPORTED_TAG", "Tag doesn't support NDEF", null);
            }
        } catch (IOException e) {
            throw new NfcMultiReadInFlutterException("IO_ERROR", "Tag communication error: " + e.getMessage(), null);
        } catch (FormatException e) {
            throw new NfcMultiReadInFlutterException("FORMAT_ERROR", "Message format error: " + e.getMessage(), null);
        }
    }

    private void handleNdefTagWrite(Ndef ndef, NdefMessage message) throws IOException, FormatException, NfcMultiReadInFlutterException {
        ndef.connect();
        try {
            // Check if tag has enough capacity
            if (ndef.getMaxSize() < message.toByteArray().length) {
                Map<String, Object> details = new HashMap<>();
                details.put("requiredSize", message.toByteArray().length);
                details.put("availableSize", ndef.getMaxSize());
                throw new NfcMultiReadInFlutterException("INSUFFICIENT_CAPACITY", "Tag capacity too small", details);
            }

            // Check if tag is writable
            if (!ndef.isWritable()) {
                throw new NfcMultiReadInFlutterException("READ_ONLY_TAG", "Tag is read-only", null);
            }

            // Write the message
            ndef.writeNdefMessage(message);
        } finally {
            try {
                ndef.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error closing NDEF tag", e);
            }
        }
    }

    private void handleNdefFormatableTagWrite(NdefFormatable formatable, NdefMessage message) throws IOException, FormatException {
        formatable.connect();
        try {
            formatable.format(message);
        } finally {
            try {
                formatable.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error closing NdefFormatable tag", e);
            }
        }
    }

    public class NfcMultiReadInFlutterException extends Exception {
        public final String code;
        public final Object details;

        public NfcMultiReadInFlutterException(String code, String message, Object details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

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