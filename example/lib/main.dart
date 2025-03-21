import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:nfc_multi_read_in_flutter/nfc_multi_read_in_flutter.dart';

import './read_example_screen.dart';
import './write_example_screen.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text("NFC in Flutter examples"),
        ),
        body: Builder(builder: (context) {
          return ListView(
            children: <Widget>[
              ListTile(
                title: const Text("Read NFC"),
                onTap: () {
                  Navigator.pushNamed(context, "/read_example");
                },
              ),
              ListTile(
                title: const Text("Write NFC"),
                onTap: () {
                  Navigator.pushNamed(context, "/write_example");
                },
              ),
            ],
          );
        }),
      ),
      routes: {
        "/read_example": (context) => const ReadExampleScreen(),
        "/write_example": (context) => const WriteExampleScreen(),
      },
    );
  }
}

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // _stream is a subscription to the stream returned by `NFC.read()`.
  // The subscription is stored in state so the stream can be canceled later
  StreamSubscription<NDEFMultiMessage>? _stream;

  // _tags is a list of scanned tags
  final List<NDEFMultiMessage> _tags = [];

  bool _supportsNFC = false;

  // _readNFC() calls `NFC.readNDEF()` and stores the subscription and scanned
  // tags in state
  void _readNFC(BuildContext context) {
    try {
      // ignore: cancel_subscriptions
      StreamSubscription<NDEFMultiMessage> subscription = NFCMultiRead.readNDEF().listen(
          (tag) {
        // On new tag, add it to state
        setState(() {
          _tags.insert(0, tag);
        });
      },
          // When the stream is done, remove the subscription from state
          onDone: () {
        setState(() {
          _stream = null;
        });
      },
          // Errors are unlikely to happen on Android unless the NFC tags are
          // poorly formatted or removed too soon, however on iOS at least one
          // error is likely to happen. NFCMultiUserCanceledSessionException will
          // always happen unless you call readNDEF() with the `throwOnUserCancel`
          // argument set to false.
          // NFCMultiSessionTimeoutException will be thrown if the session timer exceeds
          // 60 seconds (iOS only).
          // And then there are of course errors for unexpected stuff. Good fun!
          onError: (e) {
        setState(() {
          _stream = null;
        });

        if (e is! NFCMultiUserCanceledSessionException) {
          showDialog(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text("Error!"),
              content: Text(e.toString()),
            ),
          );
        }
      });

      setState(() {
        _stream = subscription;
      });
    } catch (err) {
      if (kDebugMode) {
        print("error: $err");
      }
    }
  }

  // _stopReading() cancels the current reading stream
  void _stopReading() {
    _stream?.cancel();
    setState(() {
      _stream = null;
    });
  }

  @override
  void initState() {
    super.initState();
    NFCMultiRead.isNDEFSupported.then((supported) {
      setState(() {
        _supportsNFC = true;
      });
    });
  }

  @override
  void dispose() {
    super.dispose();
    _stream?.cancel();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(
        primarySwatch: Colors.grey,
      ),
      home: Scaffold(
        appBar: AppBar(
          backgroundColor: Colors.white,
          title: const Text('NFC in Flutter'),
          actions: <Widget>[
            Builder(
              builder: (context) {
                if (!_supportsNFC) {
                  return TextButton(
                    style: ButtonStyle(
                      foregroundColor: WidgetStateProperty.all<Color>(Colors.blue),
                    ),
                    onPressed: () { },
                    child: const Text("NFC unsupported"),
                  );
                }
                return TextButton(
                  style: ButtonStyle(
                    foregroundColor: WidgetStateProperty.all<Color>(Colors.blue),
                  ),
                  onPressed: () {
                    if (_stream == null) {
                      _readNFC(context);
                    } else {
                      _stopReading();
                    }
                  },
                  child: Text(_stream == null ? "Start reading" : "Stop reading"),
                );
              },
            ),
            IconButton(
              icon: const Icon(Icons.clear_all),
              onPressed: () {
                setState(() {
                  _tags.clear();
                });
              },
              tooltip: "Clear",
            ),
          ],
        ),
        // Render list of scanned tags
        body: ListView.builder(
          itemCount: _tags.length,
          itemBuilder: (context, index) {
            const TextStyle payloadTextStyle = TextStyle(
              fontSize: 15,
              color: Color(0xFF454545),
            );

            return Padding(
              padding: const EdgeInsets.fromLTRB(20, 10, 20, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  const Text("NDEF Tag",
                      style: TextStyle(fontWeight: FontWeight.bold)),
                  Builder(
                    builder: (context) {
                      // Build list of records
                      List<Widget> records = [];
                      for (int i = 0; i < _tags[index].records.length; i++) {
                        records.add(Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: <Widget>[
                            Text(
                              "Record ${i + 1} - ${_tags[index].records[i].type}",
                              style: const TextStyle(
                                fontSize: 13,
                                color: Color(0xFF666666),
                              ),
                            ),
                            Text(
                              _tags[index].records[i].payload!,
                              style: payloadTextStyle,
                            ),
                            Text(
                              _tags[index].records[i].data!,
                              style: payloadTextStyle,
                            ),
                          ],
                        ));
                      }
                      return Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: records,
                      );
                    },
                  )
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
