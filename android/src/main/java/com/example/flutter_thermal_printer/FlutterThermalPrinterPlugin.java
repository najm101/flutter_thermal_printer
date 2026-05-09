package com.example.flutter_thermal_printer;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.EventChannel;

/** FlutterThermalPrinterPlugin */
public class FlutterThermalPrinterPlugin implements FlutterPlugin, MethodCallHandler {
  private MethodChannel channel;
  private EventChannel eventChannel;
  private EventChannel statusEventChannel;
  private Context context;
  private UsbPrinter usbPrinter;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_thermal_printer");
    eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_thermal_printer/events");
    statusEventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_thermal_printer/status");

    channel.setMethodCallHandler(this);
    context = flutterPluginBinding.getApplicationContext();
    usbPrinter = new UsbPrinter(context);
    eventChannel.setStreamHandler(usbPrinter);

    statusEventChannel.setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object arguments, EventChannel.EventSink events) {
        if (arguments instanceof Map) {
          Map<?, ?> args = (Map<?, ?>) arguments;
          String vendorId = (String) args.get("vendorId");
          String productId = (String) args.get("productId");
          Boolean useAsb = (Boolean) args.get("useAsb");
          usbPrinter.startStatusStream(vendorId, productId, Boolean.TRUE.equals(useAsb), events);
        }
      }

      @Override
      public void onCancel(Object arguments) {
        if (arguments instanceof Map) {
          Map<?, ?> args = (Map<?, ?>) arguments;
          String vendorId = (String) args.get("vendorId");
          String productId = (String) args.get("productId");
          usbPrinter.stopStatusStream(vendorId, productId);
        }
      }
    });
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
      switch (call.method) {
          case "getPlatformVersion":
              result.success("Android " + android.os.Build.VERSION.RELEASE);
              break;
          case "getUsbDevicesList":
              result.success(usbPrinter.getUsbDevicesList());
              break;
          case "connect": {
              String vendorId = call.argument("vendorId");
              String productId = call.argument("productId");
              usbPrinter.connect(vendorId, productId);
              result.success(false);
              break;
          }
          case "disconnect": {
              String vendorId = call.argument("vendorId");
              String productId = call.argument("productId");
              result.success(usbPrinter.disconnect(vendorId, productId));
              break;
          }
          case "printText": {
              String vendorId = call.argument("vendorId");
              String productId = call.argument("productId");
              List<Integer> data = call.argument("data");
              usbPrinter.printText(vendorId, productId, data);
              result.success(true);
              break;
          }
          case "isConnected": {
              String vendorId = call.argument("vendorId");
              String productId = call.argument("productId");
              result.success(usbPrinter.isConnected(vendorId, productId));
              break;
          }
          case "getPrinterStatus": {
              String vendorId = call.argument("vendorId");
              String productId = call.argument("productId");
              result.success(usbPrinter.getPrinterStatus(vendorId, productId));
              break;
          }
          default:
              result.notImplemented();
              break;
      }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    statusEventChannel.setStreamHandler(null);
  }
}
