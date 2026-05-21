package com.example.flutter_thermal_printer;

import static android.content.Context.USB_SERVICE;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.flutter.plugin.common.EventChannel;

public class UsbPrinter implements EventChannel.StreamHandler {
    @SuppressLint("StaticFieldLeak")
    private static Context context;

    private static final String ACTION_USB_PERMISSION = "com.example.flutter_thermal_printer.USB_PERMISSION";
    private static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    private static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    private static final String TAG = "FPP";

    // ESC @ — resets printer state (clears formatting, partial command buffers)
    private static final byte[] ESC_AT = {0x1B, 0x40};

    // Device attach/detach event sink
    private EventChannel.EventSink events;

    // Persistent connections keyed by "vendorId_productId"
    private final Map<String, UsbDeviceConnection> connections = new HashMap<>();
    private final Map<String, UsbEndpoint> endpointsOut = new HashMap<>();
    private final Map<String, UsbEndpoint> endpointsIn = new HashMap<>();

    // All USB I/O is serialized through this single-thread executor
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();

    // Polling scheduler — submits work to usbExecutor, never touches USB directly
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> pollTasks = new HashMap<>();
    private final Map<String, String> lastSignatureMap = new HashMap<>();
    private final Map<String, Map<String, Object>> lastFullStatusMap = new HashMap<>();
    private EventChannel.EventSink statusEventSink;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ASB (Automatic Status Back) state
    private volatile boolean asbActive = false;
    private volatile boolean streamActive = false;
    private Thread asbThread;
    private final AtomicInteger streamGeneration = new AtomicInteger(0);
    private static final byte[] GS_A_ENABLE  = {0x1D, 0x61, 0x0F};
    private static final byte[] GS_A_DISABLE = {0x1D, 0x61, 0x00};

    private BroadcastReceiver usbStateChangeReceiver;
    private static PendingIntent mPermissionIntent;

    // Last printer the app asked to connect to — used by self-heal to auto-reopen
    private String connectionVendorId;
    private String connectionProductId;
    private Integer requestingPermission = 0;

    UsbPrinter(Context context) {
        UsbPrinter.context = context;
        mPermissionIntent = PendingIntent.getActivity(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    }

    // -------------------------------------------------------------------------
    // EventChannel.StreamHandler — device attach/detach stream
    // -------------------------------------------------------------------------

    private void createUsbStateChangeReceiver() {
        usbStateChangeReceiver = new BroadcastReceiver() {
            @SuppressLint("LongLogTag")
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (Objects.equals(action, ACTION_USB_ATTACHED)) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    Log.d(TAG, "ACTION_USB_ATTACHED");

                    // Self-heal: if the attached device is the one the app wants,
                    // reopen the connection automatically (or request permission once).
                    if (device != null && matchesIntendedDevice(device)) {
                        UsbManager m = (UsbManager) context.getSystemService(USB_SERVICE);
                        if (m.hasPermission(device)) {
                            Log.d(TAG, "Self-heal: reopening connection for " + deviceKey(device));
                            boolean opened = openAndStoreConnection(device, m);
                            if (opened) {
                                sendResetPrinter(deviceKey(device));
                            }
                        } else {
                            Log.d(TAG, "Self-heal: requesting permission for " + deviceKey(device));
                            PendingIntent pi = PendingIntent.getBroadcast(
                                context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                            m.requestPermission(device, pi);
                        }
                    }
                    sendDevice(device);

                } else if (Objects.equals(action, ACTION_USB_DETACHED)) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    Log.d(TAG, "ACTION_USB_DETACHED");
                    if (device != null) {
                        String key = deviceKey(device);
                        stopPoll(key);
                        stopAsbThread();
                        asbActive = false;
                        closeConnection(key);
                        // Reset permission retry counter so the next attach can request again
                        requestingPermission = 0;
                    }
                    sendDevice(device);

                } else if (Objects.equals(action, ACTION_USB_PERMISSION)) {
                    Log.d(TAG, "ACTION_USB_PERMISSION granted=" + intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                    synchronized (this) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        boolean permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        if (permissionGranted && device != null) {
                            Log.d(TAG, "Permission granted for device " + device);
                            UsbManager m = (UsbManager) context.getSystemService(USB_SERVICE);
                            boolean opened = openAndStoreConnection(device, m);
                            if (opened) {
                                // Send ESC @ to clear any partial command left from the interrupted session
                                sendResetPrinter(deviceKey(device));
                            }
                            sendDevice(device);
                        } else {
                            Log.d(TAG, "Permission denied for device");
                            if (connectionVendorId != null && connectionProductId != null) {
                                connect(connectionVendorId, connectionProductId);
                            }
                        }
                    }
                }
            }
        };
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        this.events = events;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_ATTACHED);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        createUsbStateChangeReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbStateChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbStateChangeReceiver, filter);
        }
    }

    @Override
    public void onCancel(Object arguments) {
        if (events != null) {
            context.unregisterReceiver(usbStateChangeReceiver);
            events = null;
        }
    }

    // -------------------------------------------------------------------------
    // Status stream
    // -------------------------------------------------------------------------

    public void startStatusStream(String vendorId, String productId, boolean useAsb, EventChannel.EventSink sink) {
        statusEventSink = sink;
        String key = deviceKey(vendorId, productId);
        stopPoll(key);
        stopAsbThread();
        streamGeneration.incrementAndGet();
        streamActive = true;

        if (useAsb) {
            UsbDeviceConnection connection = connections.get(key);
            UsbEndpoint epOut = endpointsOut.get(key);
            UsbEndpoint epIn  = endpointsIn.get(key);

            if (connection == null || epOut == null || epIn == null) {
                Log.d(TAG, "startStatusStream: no connection, cannot start ASB");
                return;
            }

            usbExecutor.submit(() -> {
                connection.bulkTransfer(epOut, GS_A_ENABLE, GS_A_ENABLE.length, 2000);
            });

            asbActive = true;
            startAsbLoop(key, connection, epIn);
            Log.d(TAG, "ASB mode started for " + key);
        } else {
            asbActive = false;
            startPolling(vendorId, productId, key);
            Log.d(TAG, "Polling mode started for " + key);
        }
    }

    public void stopStatusStream(String vendorId, String productId) {
        String key = deviceKey(vendorId, productId);
        streamActive = false;
        streamGeneration.incrementAndGet();
        stopPoll(key);
        stopAsbThread();

        if (asbActive) {
            asbActive = false;
            UsbDeviceConnection connection = connections.get(key);
            UsbEndpoint epOut = endpointsOut.get(key);
            if (connection != null && epOut != null) {
                usbExecutor.submit(() ->
                    connection.bulkTransfer(epOut, GS_A_DISABLE, GS_A_DISABLE.length, 1000));
            }
        }
        statusEventSink = null;
    }

    private void startAsbLoop(String key, UsbDeviceConnection connection, UsbEndpoint epIn) {
        final int myGeneration = streamGeneration.get();
        asbThread = new Thread(() -> {
            byte[] buf = new byte[4];
            while (streamActive && asbActive && streamGeneration.get() == myGeneration) {
                int read = connection.bulkTransfer(epIn, buf, 4, 2000);
                if (read == 4 && streamGeneration.get() == myGeneration) {
                    Map<String, Object> status = parseAsbResponse(buf);
                    String sig = statusSignature(status);
                    if (!sig.equals(lastSignatureMap.get(key))) {
                        lastSignatureMap.put(key, sig);
                        lastFullStatusMap.put(key, status);
                        mainHandler.post(() -> {
                            if (statusEventSink != null) statusEventSink.success(status);
                        });
                    }
                }
            }
            Log.d(TAG, "ASB thread exited for " + key + " (gen=" + myGeneration + ")");
        });
        asbThread.setDaemon(true);
        asbThread.setName("USB-ASB-" + key);
        asbThread.start();
    }

    private void startPolling(String vendorId, String productId, String key) {
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() ->
            usbExecutor.submit(() -> {
                Map<String, Object> status = readStatusInternal(vendorId, productId);
                String sig = statusSignature(status);
                if (!sig.equals(lastSignatureMap.get(key))) {
                    lastSignatureMap.put(key, sig);
                    lastFullStatusMap.put(key, status);
                    mainHandler.post(() -> {
                        if (statusEventSink != null) statusEventSink.success(status);
                    });
                }
            }),
        0, 3, TimeUnit.SECONDS);
        pollTasks.put(key, task);
    }

    private void stopAsbThread() {
        Thread t = asbThread;
        asbThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> getUsbDevicesList() {
        UsbManager m = (UsbManager) context.getSystemService(USB_SERVICE);
        HashMap<String, UsbDevice> usbDevices = m.getDeviceList();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
            UsbDevice device = entry.getValue();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                HashMap<String, Object> deviceData = new HashMap<>();
                deviceData.put("name", device.getProductName());
                deviceData.put("vendorId", String.valueOf(device.getVendorId()));
                deviceData.put("productId", String.valueOf(device.getProductId()));
                deviceData.put("connected", m.hasPermission(device));
                data.add(deviceData);
            }
        }
        return data;
    }

    public void connect(String vendorId, String productId) {
        connectionVendorId = vendorId;
        connectionProductId = productId;
        UsbManager m = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice device = findDevice(m, vendorId, productId);

        if (device == null) {
            Log.d(TAG, "connect: device not found " + vendorId + "_" + productId);
            return;
        }

        if (!m.hasPermission(device) && requestingPermission < 2) {
            requestingPermission++;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            m.requestPermission(device, permissionIntent);
        } else {
            requestingPermission = 0;
            openAndStoreConnection(device, m);
            sendDevice(device);
        }
    }

    public boolean disconnect(String vendorId, String productId) {
        String key = deviceKey(vendorId, productId);
        stopPoll(key);
        stopAsbThread();
        asbActive = false;
        closeConnection(key);

        // Clear intent so self-heal doesn't auto-reconnect after an explicit disconnect
        if (vendorId.equals(connectionVendorId) && productId.equals(connectionProductId)) {
            connectionVendorId = null;
            connectionProductId = null;
        }

        UsbManager m = (UsbManager) context.getSystemService(USB_SERVICE);
        UsbDevice device = findDevice(m, vendorId, productId);

        if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && events != null) {
            HashMap<String, Object> deviceData = new HashMap<>();
            deviceData.put("name", device.getProductName());
            deviceData.put("vendorId", vendorId);
            deviceData.put("productId", productId);
            deviceData.put("connected", false);
            events.success(deviceData);
        }
        return true;
    }

    public boolean isConnected(String vendorId, String productId) {
        UsbManager m = (UsbManager) context.getSystemService(USB_SERVICE);
        UsbDevice device = findDevice(m, vendorId, productId);
        if (device == null) return false;
        return m.hasPermission(device);
    }

    /**
     * Attempts to print bytes to the device.
     * Runs a pre-flight printer status check (cover, paper, online) and the USB
     * write as a single atomic task on usbExecutor — no other USB I/O can interleave.
     * Returns a result map with keys:
     *   success (boolean), bytesWritten (int), errorCode (String), message (String)
     */
    public Map<String, Object> printText(String vendorId, String productId, List<Integer> bytes) {
        String key = deviceKey(vendorId, productId);

        // Lazy self-heal: if there's no live connection, try to reopen before failing
        if (!connections.containsKey(key)) {
            Log.d(TAG, "printText: no connection for " + key + ", attempting reopen");
            boolean reopened = attemptReopen(vendorId, productId);
            if (!reopened) {
                return errorResult("notConnected", "No active connection and reopen failed for " + key);
            }
        }

        byte[] data = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            data[i] = bytes.get(i).byteValue();
        }

        // Run pre-flight status check + write as one atomic task on usbExecutor.
        // Timeout: up to 8s for status reads (4 x 2000ms DLE EOT) + 5s for write = 15s.
        Future<Map<String, Object>> future = usbExecutor.submit(() -> {
            UsbDeviceConnection connection = connections.get(key);
            UsbEndpoint epOut = endpointsOut.get(key);
            UsbEndpoint epIn  = endpointsIn.get(key);

            if (connection == null || epOut == null) {
                return errorResult("notConnected", "Endpoints missing after reopen for " + key);
            }

            // Pre-flight: read live status when IN endpoint is available and not in
            // ASB mode (ASB thread owns the IN endpoint; use cached status instead).
            if (epIn != null) {
                Map<String, Object> status = asbActive
                    ? lastFullStatusMap.getOrDefault(key, defaultStatus())
                    : readStatusInternal(vendorId, productId);

                boolean online    = Boolean.TRUE.equals(status.get("isOnline"));
                boolean hasPaper  = Boolean.TRUE.equals(status.get("hasPaper"));
                boolean coverOpen = Boolean.TRUE.equals(status.get("isCoverOpen"));

                if (!online) {
                    return errorResult("printerOffline", "Printer is offline or not responding");
                }
                if (coverOpen) {
                    return errorResult("coverOpen", "Printer cover is open");
                }
                if (!hasPaper) {
                    return errorResult("noPaper", "Printer is out of paper");
                }
            }

            // Send bytes
            int written = connection.bulkTransfer(epOut, data, data.length, 5000);
            if (written < 0) {
                // Stale connection — drop it so the next print triggers a fresh reopen
                closeConnection(key);
                return errorResult("writeFailed", "bulkTransfer returned " + written + " for " + key);
            }
            if (written < data.length) {
                return errorResult("writeIncomplete",
                    "Only " + written + "/" + data.length + " bytes written for " + key);
            }
            return successResult(written);
        });

        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return errorResult("writeTimeout", "Print operation timed out after 15s for " + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("writeTimeout", "Interrupted while waiting for USB write");
        } catch (Exception e) {
            return errorResult("unknown", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** Sends ESC @ to reset printer formatting state. Returns true if a connection existed. */
    public boolean resetPrinter(String vendorId, String productId) {
        String key = deviceKey(vendorId, productId);
        if (!connections.containsKey(key)) return false;
        sendResetPrinter(key);
        return true;
    }

    public Map<String, Object> getPrinterStatus(String vendorId, String productId) {
        if (asbActive) {
            Map<String, Object> cached = lastFullStatusMap.get(deviceKey(vendorId, productId));
            return cached != null ? cached : defaultStatus();
        }
        Future<Map<String, Object>> future = usbExecutor.submit(() ->
                readStatusInternal(vendorId, productId));
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "getPrinterStatus failed", e);
            return defaultStatus();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Tries to reopen the connection for the given device.
     * - If the device is found and permission is already granted: opens and returns true.
     * - If permission is missing: fires a single requestPermission() and returns false
     *   (the print that triggered this will fail, but the permission callback will
     *   reopen the connection so the next print can succeed).
     * - If the device isn't found at all: returns false.
     */
    private boolean attemptReopen(String vendorId, String productId) {
        UsbManager m = (UsbManager) context.getSystemService(USB_SERVICE);
        UsbDevice device = findDevice(m, vendorId, productId);
        if (device == null) {
            Log.d(TAG, "attemptReopen: device " + vendorId + "_" + productId + " not found");
            return false;
        }
        if (m.hasPermission(device)) {
            Log.d(TAG, "attemptReopen: permission already granted, reopening");
            boolean opened = openAndStoreConnection(device, m);
            if (opened) {
                sendResetPrinter(deviceKey(device));
            }
            return opened;
        }
        // Request permission once; this print will fail but the callback will reopen
        Log.d(TAG, "attemptReopen: requesting permission for " + vendorId + "_" + productId);
        PendingIntent pi = PendingIntent.getBroadcast(
            context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        m.requestPermission(device, pi);
        return false;
    }

    /** Submits ESC @ to the usbExecutor — must not be called from within usbExecutor itself. */
    private void sendResetPrinter(String key) {
        UsbDeviceConnection connection = connections.get(key);
        UsbEndpoint epOut = endpointsOut.get(key);
        if (connection == null || epOut == null) return;
        usbExecutor.submit(() ->
            connection.bulkTransfer(epOut, ESC_AT, ESC_AT.length, 1000));
        Log.d(TAG, "Sent ESC @ reset to " + key);
    }

    /** Returns true if the given device matches the last printer the app called connect() on. */
    private boolean matchesIntendedDevice(UsbDevice device) {
        return connectionVendorId != null && connectionProductId != null
            && String.valueOf(device.getVendorId()).equals(connectionVendorId)
            && String.valueOf(device.getProductId()).equals(connectionProductId);
    }

    private Map<String, Object> readStatusInternal(String vendorId, String productId) {
        Map<String, Object> status = defaultStatus();
        String key = deviceKey(vendorId, productId);
        UsbDeviceConnection connection = connections.get(key);
        UsbEndpoint epOut = endpointsOut.get(key);
        UsbEndpoint epIn = endpointsIn.get(key);

        if (connection == null || epOut == null || epIn == null) {
            return status;
        }

        byte[] cmd1 = {0x10, 0x04, 0x01};
        connection.bulkTransfer(epOut, cmd1, cmd1.length, 2000);
        byte[] resp1 = new byte[1];
        int read1 = connection.bulkTransfer(epIn, resp1, 1, 2000);

        byte[] cmd4 = {0x10, 0x04, 0x04};
        connection.bulkTransfer(epOut, cmd4, cmd4.length, 2000);
        byte[] resp4 = new byte[1];
        int read4 = connection.bulkTransfer(epIn, resp4, 1, 2000);

        boolean isOnline = read1 > 0 && (resp1[0] & 0x08) == 0;
        status.put("isOnline", isOnline);
        status.put("rawByte1", read1 > 0 ? (resp1[0] & 0xFF) : -1);
        status.put("rawByte4", read4 > 0 ? (resp4[0] & 0xFF) : -1);

        if (read1 > 0) {
            status.put("isWaitingForRecovery", (resp1[0] & 0x20) != 0);
            status.put("drawerKickOutPin", (resp1[0] & 0x04) != 0);
        }
        if (read4 > 0) {
            boolean paperEnd = (resp4[0] & 0x60) != 0;
            boolean paperNearEnd = (resp4[0] & 0x0C) != 0;
            status.put("hasPaper", !paperEnd);
            status.put("isPaperNearEnd", paperNearEnd && !paperEnd);
        }

        if (!isOnline) {
            byte[] cmd2 = {0x10, 0x04, 0x02};
            connection.bulkTransfer(epOut, cmd2, cmd2.length, 2000);
            byte[] resp2 = new byte[1];
            int read2 = connection.bulkTransfer(epIn, resp2, 1, 2000);

            byte[] cmd3 = {0x10, 0x04, 0x03};
            connection.bulkTransfer(epOut, cmd3, cmd3.length, 2000);
            byte[] resp3 = new byte[1];
            int read3 = connection.bulkTransfer(epIn, resp3, 1, 2000);

            status.put("rawByte2", read2 > 0 ? (resp2[0] & 0xFF) : -1);
            status.put("rawByte3", read3 > 0 ? (resp3[0] & 0xFF) : -1);

            if (read2 > 0) {
                status.put("isCoverOpen", (resp2[0] & 0x04) != 0);
                status.put("hasCutterError", (resp2[0] & 0x40) != 0);
                status.put("hasUnrecoverableError", (resp2[0] & 0x20) != 0);
            }
            if (read3 > 0) {
                boolean cutterErr = (resp3[0] & 0x08) != 0 || (resp3[0] & 0x40) != 0;
                boolean hardErr = (resp3[0] & 0x20) != 0;
                status.put("hasCutterError", (boolean) status.get("hasCutterError") || cutterErr);
                status.put("hasUnrecoverableError", (boolean) status.get("hasUnrecoverableError") || hardErr);
            }
        }

        return status;
    }

    private static String statusSignature(Map<String, Object> status) {
        return status.get("isOnline")             + "," +
               status.get("hasPaper")             + "," +
               status.get("isPaperNearEnd")       + "," +
               status.get("isCoverOpen")          + "," +
               status.get("hasCutterError")       + "," +
               status.get("hasUnrecoverableError") + "," +
               status.get("isWaitingForRecovery") + "," +
               status.get("drawerKickOutPin");
    }

    private Map<String, Object> parseAsbResponse(byte[] buf) {
        Map<String, Object> status = defaultStatus();
        boolean isOnline = (buf[0] & 0x08) == 0;
        status.put("isOnline",             isOnline);
        status.put("isWaitingForRecovery", (buf[0] & 0x20) != 0);
        status.put("drawerKickOutPin",     (buf[0] & 0x04) != 0);
        status.put("isCoverOpen",          (buf[1] & 0x04) != 0);
        boolean cutterErr = (buf[1] & 0x40) != 0 || (buf[2] & 0x08) != 0 || (buf[2] & 0x40) != 0;
        boolean hardErr   = (buf[1] & 0x20) != 0 || (buf[2] & 0x20) != 0;
        status.put("hasCutterError",        cutterErr);
        status.put("hasUnrecoverableError", hardErr);
        boolean paperEnd     = (buf[3] & 0x60) != 0;
        boolean paperNearEnd = (buf[3] & 0x0C) != 0;
        status.put("hasPaper",       !paperEnd);
        status.put("isPaperNearEnd", paperNearEnd && !paperEnd);
        status.put("rawByte1", buf[0] & 0xFF);
        status.put("rawByte2", buf[1] & 0xFF);
        status.put("rawByte3", buf[2] & 0xFF);
        status.put("rawByte4", buf[3] & 0xFF);
        return status;
    }

    private boolean openAndStoreConnection(UsbDevice device, UsbManager m) {
        String key = deviceKey(device);
        closeConnection(key);

        UsbDeviceConnection connection = m.openDevice(device);
        if (connection == null) {
            Log.d(TAG, "openDevice failed for " + key);
            return false;
        }
        connection.claimInterface(device.getInterface(0), true);

        UsbEndpoint epOut = null;
        UsbEndpoint epIn = null;
        for (int i = 0; i < device.getInterface(0).getEndpointCount(); i++) {
            UsbEndpoint ep = device.getInterface(0).getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) epOut = ep;
                else if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
            }
        }

        connections.put(key, connection);
        if (epOut != null) endpointsOut.put(key, epOut);
        if (epIn != null) endpointsIn.put(key, epIn);
        Log.d(TAG, "Opened connection for " + key + " (IN=" + (epIn != null) + " OUT=" + (epOut != null) + ")");
        return true;
    }

    private void closeConnection(String key) {
        UsbDeviceConnection connection = connections.remove(key);
        endpointsOut.remove(key);
        endpointsIn.remove(key);
        if (connection != null) {
            connection.close();
            Log.d(TAG, "Closed connection for " + key);
        }
    }

    private void stopPoll(String key) {
        ScheduledFuture<?> existing = pollTasks.remove(key);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void sendDevice(UsbDevice device) {
        if (device == null || events == null) {
            Log.d(TAG, "sendDevice: device or events is null");
            return;
        }
        boolean connected = isConnected(
            String.valueOf(device.getVendorId()),
            String.valueOf(device.getProductId()));
        HashMap<String, Object> deviceData = new HashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            deviceData.put("name", device.getProductName());
        }
        deviceData.put("vendorId", String.valueOf(device.getVendorId()));
        deviceData.put("productId", String.valueOf(device.getProductId()));
        deviceData.put("connected", connected);
        Log.d(TAG, "Sending device data: " + deviceData);
        events.success(deviceData);
    }

    private UsbDevice findDevice(UsbManager m, String vendorId, String productId) {
        for (Map.Entry<String, UsbDevice> entry : m.getDeviceList().entrySet()) {
            UsbDevice d = entry.getValue();
            if (String.valueOf(d.getVendorId()).equals(vendorId) &&
                    String.valueOf(d.getProductId()).equals(productId)) {
                return d;
            }
        }
        return null;
    }

    private static String deviceKey(UsbDevice device) {
        return device.getVendorId() + "_" + device.getProductId();
    }

    private static String deviceKey(String vendorId, String productId) {
        return vendorId + "_" + productId;
    }

    private static Map<String, Object> successResult(int bytesWritten) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("bytesWritten", bytesWritten);
        r.put("errorCode", "");
        r.put("message", "");
        return r;
    }

    private static Map<String, Object> errorResult(String code, String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("bytesWritten", 0);
        r.put("errorCode", code);
        r.put("message", message);
        Log.d(TAG, "Print error [" + code + "]: " + message);
        return r;
    }

    private static Map<String, Object> defaultStatus() {
        Map<String, Object> s = new HashMap<>();
        s.put("isOnline", false);
        s.put("hasPaper", false);
        s.put("isPaperNearEnd", false);
        s.put("isCoverOpen", false);
        s.put("hasCutterError", false);
        s.put("hasUnrecoverableError", false);
        s.put("isWaitingForRecovery", false);
        s.put("drawerKickOutPin", false);
        s.put("rawByte1", -1);
        s.put("rawByte2", -1);
        s.put("rawByte3", -1);
        s.put("rawByte4", -1);
        return s;
    }
}
