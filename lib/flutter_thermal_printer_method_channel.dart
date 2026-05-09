import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_thermal_printer_platform_interface.dart';
import 'utils/printer.dart';
import 'utils/printer_status.dart';

/// An implementation of [FlutterThermalPrinterPlatform] that uses method channels.
class MethodChannelFlutterThermalPrinter extends FlutterThermalPrinterPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_thermal_printer');

  // Cached broadcast streams keyed by "vendorId_productId_useAsb".
  // Widget rebuilds (setState) share the same stream instead of creating a
  // new EventChannel subscription each time, which was causing the Java-side
  // ASB thread to be torn down and restarted on every rebuild.
  final Map<String, Stream<PrinterStatus>> _statusStreams = {};
  final Map<String, StreamSubscription<dynamic>> _statusSubscriptions = {};
  final Map<String, StreamController<PrinterStatus>> _statusControllers = {};

  @override
  Future<String?> getPlatformVersion() async {
    final version =
        await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  @override
  Future<dynamic> startUsbScan() async =>
      methodChannel.invokeMethod('getUsbDevicesList');

  @override
  Future<bool> connect(Printer device) async =>
      await methodChannel.invokeMethod('connect', device.toJson());

  @override
  Future<bool> printText(
    Printer device,
    Uint8List data, {
    String? path,
  }) async =>
      await methodChannel.invokeMethod('printText', {
        'vendorId': device.vendorId.toString(),
        'productId': device.productId.toString(),
        'name': device.name,
        'data': List<int>.from(data),
        'path': path ?? '',
      });

  @override
  Future<bool> isConnected(Printer device) async =>
      await methodChannel.invokeMethod('isConnected', device.toJson());

  @override
  Future<dynamic> convertImageToGrayscale(Uint8List? value) async =>
      methodChannel.invokeMethod('convertimage', {
        'path': List<int>.from(value!),
      });

  @override
  Future<bool> disconnect(Printer device) async =>
      await methodChannel.invokeMethod('disconnect', {
        'vendorId': device.vendorId.toString(),
        'productId': device.productId.toString(),
      });

  @override
  Future<PrinterStatus> getPrinterStatus(Printer device) async {
    final result = await methodChannel.invokeMapMethod<dynamic, dynamic>(
      'getPrinterStatus',
      {
        'vendorId': device.vendorId.toString(),
        'productId': device.productId.toString(),
      },
    );
    return PrinterStatus.fromMap(result ?? {});
  }

  @override
  Stream<PrinterStatus> printerStatusStream(Printer device, {bool useAsb = false}) {
    final key = '${device.vendorId}_${device.productId}_$useAsb';

    final existing = _statusControllers[key];
    if (existing != null && !existing.isClosed) {
      return existing.stream;
    }

    // No onCancel — the broadcast controller and its underlying EventChannel
    // subscription stay alive across widget rebuilds and setState calls.
    // Java is called exactly once (onListen) when the stream is first created,
    // and once (onCancel) when closeStatusStream() is explicitly called.
    // ignore: close_sinks — closed explicitly in closeStatusStream()
    final controller = StreamController<PrinterStatus>.broadcast();

    // ignore: cancel_subscriptions — lifetime managed by closeStatusStream()
    _statusSubscriptions[key] =
        const EventChannel('flutter_thermal_printer/status')
            .receiveBroadcastStream({
              'vendorId': device.vendorId.toString(),
              'productId': device.productId.toString(),
              'useAsb': useAsb,
            })
            .map((e) => PrinterStatus.fromMap(e as Map))
            .listen(controller.add, onError: controller.addError);

    _statusControllers[key] = controller;
    return controller.stream;
  }

  /// Explicitly stops the status stream for a device.
  /// Call this when disconnecting or when the stream is no longer needed.
  void closeStatusStream(Printer device, {bool useAsb = false}) {
    final key = '${device.vendorId}_${device.productId}_$useAsb';
    _statusSubscriptions.remove(key)?.cancel();
    _statusControllers.remove(key)?.close();
    _statusStreams.remove(key);
  }
}
