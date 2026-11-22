package com.example.flutter_thermal_printer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

  // Calls that wait on USB I/O (print, status) run here instead of on the
  // platform main thread. Their timeouts are unchanged; only the waiting moves
  // off the UI thread, so a slow printer still gets its full time, and a USB
  // device that never answers can no longer freeze the app (ANR).
  private final ExecutorService callExecutor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "FTP-call-" + CALL_THREADS.incrementAndGet());
    t.setDaemon(true);
    return t;
  });
  private static final AtomicInteger CALL_THREADS = new AtomicInteger();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  /** Runs [work] off the main thread and replies on the main thread. */
  private void replyAsync(@NonNull Result result, @NonNull java.util.concurrent.Callable<Object> work) {
    callExecutor.execute(() -> {
      Object value;
      try {
        value = work.call();
      } catch (Exception e) {
        Log.e("FPP", "async call failed", e);
        final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        mainHandler.post(() -> result.error("unknown", msg, null));
        return;
      }
      mainHandler.post(() -> result.success(value));
    });
  }

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
        if (!(arguments instanceof Map)) {
          events.error("badArguments", "Expected map with vendorId, productId, useAsb", null);
          return;
        }
        Map<?, ?> args = (Map<?, ?>) arguments;
        String vendorId = (String) args.get("vendorId");
        String productId = (String) args.get("productId");
        if (vendorId == null || productId == null) {
          events.error("badArguments", "vendorId and productId are required", null);
          return;
        }
        Boolean useAsb = (Boolean) args.get("useAsb");
        usbPrinter.startStatusStream(vendorId, productId, Boolean.TRUE.equals(useAsb), events);
      }

      @Override
      public void onCancel(Object arguments) {
        if (!(arguments instanceof Map)) return;
        Map<?, ?> args = (Map<?, ?>) arguments;
        String vendorId = (String) args.get("vendorId");
        String productId = (String) args.get("productId");
        if (vendorId != null && productId != null) {
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
              // Older callers don't send the flag: keep the previous behaviour
              // (take the device over) for them.
              Boolean allowTakeover = call.argument("allowTakeover");
              usbPrinter.connect(vendorId, productId, allowTakeover == null || allowTakeover);
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
              Boolean useCompatMode = call.argument("useCompatMode");
              replyAsync(result, () -> Boolean.TRUE.equals(useCompatMode)
                  ? usbPrinter.printTextCompat(vendorId, productId, data)
                  : usbPrinter.printText(vendorId, productId, data));
              break;
          }
          case "resetPrinter": {
              String vendorId = call.argument("vendorId");
              String productId = call.argument("productId");
              result.success(usbPrinter.resetPrinter(vendorId, productId));
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
              replyAsync(result, () -> usbPrinter.getPrinterStatus(vendorId, productId));
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
