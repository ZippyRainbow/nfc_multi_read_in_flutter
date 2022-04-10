package me.ZippyRainbow.nfc_multi_read_in_flutter;

class NfcMultiReadInFlutterException extends Exception {
    String code;
    String message;
    Object details;

    NfcMultiReadInFlutterException(String code, String message, Object details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }
}
