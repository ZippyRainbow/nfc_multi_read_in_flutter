import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:nfc_multi_read_in_flutter/nfc_multi_read_in_flutter.dart';

class ReadExampleScreen extends StatefulWidget {
  const ReadExampleScreen({Key? key}) : super(key: key);

  @override
  _ReadExampleScreenState createState() => _ReadExampleScreenState();
}

class _ReadExampleScreenState extends State<ReadExampleScreen> {
  StreamSubscription<NDEFMultiMessage>? _stream;

  void _startScanning() {
    setState(() {
      _stream = NFCMultiRead
          .readNDEF(alertMessage: "Custom message with readNDEF#alertMessage")
          .listen((NDEFMultiMessage message) {
        if (message.isEmpty) {
          if (kDebugMode) {
            print("Read empty NDEF message");
          }
          return;
        }
        if (kDebugMode) {
          print("Read NDEF message with ${message.records.length} records");
        }
        for (NDEFRecord record in message.records) {
          if (kDebugMode) {
            print(
              "Record '${record.id ?? "[NO ID]"}' with TNF '${record.tnf}', type '${record.type}', payload '${record.payload}' and data '${record.data}' and language code '${record.languageCode}'");
          }
        }
      }, onError: (error) {
        setState(() {
          _stream = null;
        });
        if (error is NFCMultiUserCanceledSessionException) {
          if (kDebugMode) {
            print("user canceled");
          }
        } else if (error is NFCMultiSessionTimeoutException) {
          if (kDebugMode) {
            print("session timed out");
          }
        } else {
          if (kDebugMode) {
            print("error: $error");
          }
        }
      }, onDone: () {
        setState(() {
          _stream = null;
        });
      });
    });
  }

  void _stopScanning() {
    _stream?.cancel();
    setState(() {
      _stream = null;
    });
  }

  void _toggleScan() {
    if (_stream == null) {
      _startScanning();
    } else {
      _stopScanning();
    }
  }

  @override
  void dispose() {
    super.dispose();
    _stopScanning();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Read NFC example"),
      ),
      body: Center(
          child: TextButton(
            style: ButtonStyle(
              foregroundColor: WidgetStateProperty.all<Color>(Colors.blue),
            ),
            onPressed:  _toggleScan,
            child: const Text("Toggle scan"),
          )
          ),
    );
  }
}
