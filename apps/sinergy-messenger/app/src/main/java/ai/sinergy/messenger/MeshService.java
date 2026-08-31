package ai.sinergy.messenger;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MeshService extends Service {
    private static final String CHANNEL = "sinergy_mesh_v2";
    private static final int NOTIFICATION_ID = 2202;
    private static final String PREF = "sinergy_mesh_store_v2";
    private static final String OUTBOX = "outbox";
    private static final String INBOX = "inbox";
    private static final String SEEN = "seen";
    private static final int MAX_QUEUE = 128;
    private static final int TCP_PORT = 29862;
    private static final String AWARE_SERVICE = "SINERGY_MESH_V2";
    private static final UUID BLE_SERVICE_UUID = UUID.fromString("b2d75e80-1bb9-4a3d-bd25-7167bf065201");
    private static final UUID BLE_CHAR_UUID = UUID.fromString("b2d75e80-1bb9-4a3d-bd25-7167bf065202");
    private static final Object STORE_LOCK = new Object();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicBoolean directServerRunning = new AtomicBoolean(false);
    private final Set<String> bleDialing = Collections.synchronizedSet(new HashSet<>());

    private WifiAwareSession awareSession;
    private PublishDiscoverySession awarePublish;
    private SubscribeDiscoverySession awareSubscribe;
    private volatile PeerHandle awarePeer;
    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private volatile int p2pPeers;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private volatile int blePeers;
    private volatile int awarePeers;
    private volatile long txCount;
    private volatile long rxCount;
    private volatile String lastError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();
        startWifiAware();
        startWifiDirect();
        startBle();
        handler.post(flushLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { if (awarePublish != null) awarePublish.close(); } catch (Throwable ignored) {}
        try { if (awareSubscribe != null) awareSubscribe.close(); } catch (Throwable ignored) {}
        try { if (awareSession != null) awareSession.close(); } catch (Throwable ignored) {}
        try { if (scanner != null && hasBleScan()) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        try { if (advertiser != null && hasBleAdvertise()) advertiser.stopAdvertising(advertiseCallback); } catch (Throwable ignored) {}
        try { if (gattServer != null) gattServer.close(); } catch (Throwable ignored) {}
        try { if (p2pReceiver != null) unregisterReceiver(p2pReceiver); } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "SINERGY Mesh", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Фоновая mesh-сеть Синергия-Мессенджер");
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Синергия-Мессенджер")
                .setContentText("Mesh-узел активен: Aware / Direct / BLE")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    private void startWifiAware() {
        if (Build.VERSION.SDK_INT < 26 || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return;
        WifiAwareManager manager = (WifiAwareManager) getSystemService(Context.WIFI_AWARE_SERVICE);
        if (manager == null || !manager.isAvailable()) return;
        try {
            manager.attach(new AttachCallback() {
                @Override
                public void onAttached(WifiAwareSession session) {
                    awareSession = session;
                    PublishConfig pub = new PublishConfig.Builder().setServiceName(AWARE_SERVICE).build();
                    SubscribeConfig sub = new SubscribeConfig.Builder().setServiceName(AWARE_SERVICE).build();
                    session.publish(pub, awareCallback, handler);
                    session.subscribe(sub, awareCallback, handler);
                }

                @Override
                public void onAttachFailed() {
                    lastError = "Wi-Fi Aware attach failed";
                }
            }, handler);
        } catch (Throwable t) {
            lastError = "Aware: " + t.getClass().getSimpleName();
        }
    }

    private final DiscoverySessionCallback awareCallback = new DiscoverySessionCallback() {
        @Override
        public void onPublishStarted(PublishDiscoverySession session) {
            awarePublish = session;
        }

        @Override
        public void onSubscribeStarted(SubscribeDiscoverySession session) {
            awareSubscribe = session;
        }

        @Override
        public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
            awarePeer = peerHandle;
            awarePeers = 1;
            flushAware();
        }

        @Override
        public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
            receiveFrame(new String(message, StandardCharsets.UTF_8), "aware");
        }
    };

    private void flushAware() {
        try {
            String frame = peekOutbox(this);
            if (frame == null || awarePeer == null || awareSubscribe == null) return;
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1800) return;
            awareSubscribe.sendMessage(awarePeer, (int) (System.nanoTime() & 0x7fffffff), bytes);
            txCount++;
            dropOutbox(this, new JSONObject(frame).optString("id"));
        } catch (Throwable t) {
            lastError = "Aware TX: " + t.getClass().getSimpleName();
        }
    }

    private void startWifiDirect() {
        p2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2pManager == null) return;
        p2pChannel = p2pManager.initialize(this, getMainLooper(), null);
        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                    requestP2pPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                    NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (ni != null && ni.isConnected()) requestP2pConnectionInfo();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(p2pReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(p2pReceiver, f);
        discoverP2p();
    }

    private boolean hasP2pPermission() {
        if (Build.VERSION.SDK_INT >= 33) return ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void discoverP2p() {
        if (p2pManager == null || p2pChannel == null || !hasP2pPermission()) return;
        try {
            p2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) { lastError = "P2P discover=" + reason; }
            });
        } catch (Throwable t) { lastError = "P2P: " + t.getClass().getSimpleName(); }
    }

    private void requestP2pPeers() {
        if (p2pManager == null || p2pChannel == null || !hasP2pPermission()) return;
        try {
            p2pManager.requestPeers(p2pChannel, this::onP2pPeers);
        } catch (Throwable t) { lastError = "P2P peers: " + t.getClass().getSimpleName(); }
    }

    private void onP2pPeers(WifiP2pDeviceList list) {
        p2pPeers = list == null ? 0 : list.getDeviceList().size();
        if (list == null || list.getDeviceList().isEmpty()) return;
        WifiP2pDevice peer = list.getDeviceList().iterator().next();
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peer.deviceAddress;
        try {
            if (hasP2pPermission()) p2pManager.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {}
            });
        } catch (Throwable ignored) {}
    }

    private void requestP2pConnectionInfo() {
        if (p2pManager == null || p2pChannel == null || !hasP2pPermission()) return;
        try { p2pManager.requestConnectionInfo(p2pChannel, this::onP2pConnectionInfo); } catch (Throwable ignored) {}
    }

    private void onP2pConnectionInfo(WifiP2pInfo info) {
        if (info == null || !info.groupFormed) return;
        if (info.isGroupOwner) startDirectServer();
        else if (info.groupOwnerAddress != null) sendDirect(info.groupOwnerAddress.getHostAddress());
    }

    private void startDirectServer() {
        if (!directServerRunning.compareAndSet(false, true)) return;
        io.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(TCP_PORT));
                server.setSoTimeout(15000);
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket s = server.accept(); DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()))) {
                        int n = in.readInt();
                        if (n <= 0 || n > 2_000_000) continue;
                        byte[] b = new byte[n];
                        in.readFully(b);
                        receiveFrame(new String(b, StandardCharsets.UTF_8), "wifi-direct");
                    } catch (java.net.SocketTimeoutException timeout) {
                        if (peekOutbox(this) == null) break;
                    }
                }
            } catch (Throwable t) {
                lastError = "Direct server: " + t.getClass().getSimpleName();
            } finally {
                directServerRunning.set(false);
            }
        });
    }

    private void sendDirect(String host) {
        String frame = peekOutbox(this);
        if (frame == null || host == null) return;
        io.execute(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, TCP_PORT), 5000);
                byte[] b = frame.getBytes(StandardCharsets.UTF_8);
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
                    out.writeInt(b.length);
                    out.write(b);
                    out.flush();
                }
                txCount++;
                dropOutbox(this, new JSONObject(frame).optString("id"));
            } catch (Throwable t) {
                lastError = "Direct TX: " + t.getClass().getSimpleName();
            }
        });
    }

    private void startBle() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return;
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        if (!hasBleConnect()) return;
        try {
            gattServer = bm.openGattServer(this, gattServerCallback);
            if (gattServer != null) {
                BluetoothGattService svc = new BluetoothGattService(BLE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(
                        BLE_CHAR_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
                svc.addCharacteristic(c);
                gattServer.addService(svc);
            }
        } catch (Throwable t) { lastError = "BLE GATT: " + t.getClass().getSimpleName(); }

        if (hasBleAdvertise()) {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser != null) {
                AdvertiseSettings settings = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setConnectable(true).build();
                AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(BLE_SERVICE_UUID)).setIncludeDeviceName(false).build();
                try { advertiser.startAdvertising(settings, data, advertiseCallback); } catch (Throwable ignored) {}
            }
        }
        if (hasBleScan()) {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(BLE_SERVICE_UUID)).build());
                ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
                try { scanner.startScan(filters, settings, scanCallback); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean hasBleScan() {
        return Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBleConnect() {
        return Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBleAdvertise() {
        return Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartFailure(int errorCode) { lastError = "BLE advertise=" + errorCode; }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null || !hasBleConnect()) return;
            BluetoothDevice d = result.getDevice();
            String key = d.getAddress();
            if (!bleDialing.add(key)) return;
            blePeers = Math.max(1, blePeers);
            try {
                d.connectGatt(MeshService.this, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (Throwable t) {
                bleDialing.remove(key);
            }
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (BLE_CHAR_UUID.equals(characteristic.getUuid()) && value != null) {
                receiveFrame(new String(value, StandardCharsets.UTF_8), "ble");
            }
            if (responseNeeded && gattServer != null && hasBleConnect()) {
                try { gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null); } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            byte[] data = new byte[0];
            String frame = peekOutbox(MeshService.this);
            if (frame != null) data = frame.getBytes(StandardCharsets.UTF_8);
            if (gattServer != null && hasBleConnect()) {
                try { gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data); } catch (Throwable ignored) {}
            }
        }
    };

    private final BluetoothGattCallback gattClientCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && hasBleConnect()) {
                try { gatt.discoverServices(); } catch (Throwable ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { bleDialing.remove(gatt.getDevice().getAddress()); } catch (Throwable ignored) {}
                try { gatt.close(); } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || !hasBleConnect()) return;
            BluetoothGattService svc = gatt.getService(BLE_SERVICE_UUID);
            BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(BLE_CHAR_UUID);
            String frame = peekOutbox(MeshService.this);
            if (c == null || frame == null) { try { gatt.disconnect(); } catch (Throwable ignored) {} return; }
            byte[] b = frame.getBytes(StandardCharsets.UTF_8);
            if (b.length > 480) { try { gatt.disconnect(); } catch (Throwable ignored) {} return; }
            try {
                int result;
                if (Build.VERSION.SDK_INT >= 33) result = gatt.writeCharacteristic(c, b, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                else {
                    c.setValue(b);
                    result = gatt.writeCharacteristic(c) ? 0 : -1;
                }
                if (result >= 0) {
                    txCount++;
                    dropOutbox(MeshService.this, new JSONObject(frame).optString("id"));
                }
            } catch (Throwable t) { lastError = "BLE TX: " + t.getClass().getSimpleName(); }
            try { gatt.disconnect(); } catch (Throwable ignored) {}
        }
    };

    private final Runnable flushLoop = new Runnable() {
        @Override
        public void run() {
            flushAware();
            discoverP2p();
            handler.postDelayed(this, 12_000L);
        }
    };

    private void receiveFrame(String raw, String transport) {
        try {
            JSONObject f = new JSONObject(raw);
            String id = f.optString("id", "");
            if (id.isEmpty() || seen(this, id)) return;
            markSeen(this, id);
            f.put("receivedVia", transport);
            f.put("receivedAt", System.currentTimeMillis());
            append(this, INBOX, f, MAX_QUEUE);
            rxCount++;

            int ttl = f.optInt("ttl", 0);
            if (ttl > 0) {
                JSONObject relay = new JSONObject(raw);
                relay.put("ttl", ttl - 1);
                append(this, OUTBOX, relay, MAX_QUEUE);
            }
        } catch (Throwable t) {
            lastError = "RX parse: " + t.getClass().getSimpleName();
        }
    }

    public static String enqueue(Context context, String payload) {
        try {
            JSONObject f = new JSONObject();
            f.put("id", frameId(payload));
            f.put("ttl", 6);
            f.put("createdAt", System.currentTimeMillis());
            f.put("payload", payload == null ? "" : payload);
            append(context, OUTBOX, f, MAX_QUEUE);
            return f.getString("id");
        } catch (Throwable t) {
            return "";
        }
    }

    public static JSONArray drainInbox(Context context) {
        synchronized (STORE_LOCK) {
            SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray a = parseArray(p.getString(INBOX, "[]"));
            p.edit().putString(INBOX, "[]").apply();
            return a;
        }
    }

    public static JSONObject storedStatus(Context context) {
        JSONObject o = new JSONObject();
        try {
            o.put("outbox", parseArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(OUTBOX, "[]")).length());
            o.put("inbox", parseArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(INBOX, "[]")).length());
        } catch (Throwable ignored) {}
        return o;
    }

    public JSONObject liveStatus() {
        JSONObject o = storedStatus(this);
        try {
            o.put("wifiAware", awareSession != null);
            o.put("wifiAwarePeers", awarePeers);
            o.put("wifiDirect", p2pManager != null);
            o.put("wifiDirectPeers", p2pPeers);
            o.put("bleGattServer", gattServer != null);
            o.put("blePeers", blePeers);
            o.put("tx", txCount);
            o.put("rx", rxCount);
            o.put("lastError", lastError);
        } catch (Throwable ignored) {}
        return o;
    }

    private static String peekOutbox(Context c) {
        synchronized (STORE_LOCK) {
            JSONArray a = parseArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(OUTBOX, "[]"));
            return a.length() == 0 ? null : a.optJSONObject(0).toString();
        }
    }

    private static void dropOutbox(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (STORE_LOCK) {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray a = parseArray(p.getString(OUTBOX, "[]"));
            JSONArray n = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.optJSONObject(i);
                if (f != null && !id.equals(f.optString("id"))) n.put(f);
            }
            p.edit().putString(OUTBOX, n.toString()).apply();
        }
    }

    private static void append(Context c, String key, JSONObject item, int max) {
        synchronized (STORE_LOCK) {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray old = parseArray(p.getString(key, "[]"));
            JSONArray n = new JSONArray();
            int start = Math.max(0, old.length() - max + 1);
            for (int i = start; i < old.length(); i++) n.put(old.opt(i));
            n.put(item);
            p.edit().putString(key, n.toString()).apply();
        }
    }

    private static boolean seen(Context c, String id) {
        synchronized (STORE_LOCK) {
            Set<String> s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(SEEN, Collections.emptySet());
            return s.contains(id);
        }
    }

    private static void markSeen(Context c, String id) {
        synchronized (STORE_LOCK) {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            Set<String> s = new HashSet<>(p.getStringSet(SEEN, Collections.emptySet()));
            s.add(id);
            if (s.size() > 512) {
                List<String> l = new ArrayList<>(s);
                s = new HashSet<>(l.subList(Math.max(0, l.size() - 256), l.size()));
            }
            p.edit().putStringSet(SEEN, s).apply();
        }
    }

    private static JSONArray parseArray(String s) {
        try { return new JSONArray(s == null ? "[]" : s); } catch (Throwable t) { return new JSONArray(); }
    }

    private static String frameId(String payload) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update("SINERGY-MESH-FRAME-v2".getBytes(StandardCharsets.UTF_8));
        md.update(longBytes(System.currentTimeMillis()));
        md.update(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        if (payload != null) md.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(md.digest(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] longBytes(long v) {
        return new byte[] { (byte)(v>>>56),(byte)(v>>>48),(byte)(v>>>40),(byte)(v>>>32),(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v };
    }
}
