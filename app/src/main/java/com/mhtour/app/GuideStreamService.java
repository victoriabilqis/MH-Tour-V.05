package com.mhtour.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.net.wifi.WifiManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.*;
import androidx.core.content.ContextCompat;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class GuideStreamService extends Service {
    public static final String ACTION_START = "START";
    public static final String ACTION_MIC_START = "MIC_START";
    public static final String ACTION_STOP = "STOP";
    public static final String ACTION_STATUS = "STATUS";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_PATH = "path";

    private LiveServer server;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private String code = "";
    private int port = 8080;
    private boolean micActive;
    private volatile boolean guideMuted = false;
    private volatile float guideVolume = 1.0f;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String incomingCode = intent.getStringExtra(EXTRA_CODE);
            code = incomingCode == null || incomingCode.isEmpty() ? "MHTOUR-0000" : incomingCode;
            String path = intent.getStringExtra(EXTRA_PATH);
            if (path == null) path = "";
            startForegroundNow("Siaran audio aktif • " + code);
            startServer(false, path);
        } else if (ACTION_MIC_START.equals(action)) {
            String incomingCode = intent.getStringExtra(EXTRA_CODE);
            if (incomingCode != null && !incomingCode.isEmpty()) code = incomingCode;
            if (code == null || code.isEmpty()) code = "MHTOUR-0000";
            startForegroundNow("🎙 MIC Guide LIVE • " + code);
            startServer(true, "");
        } else if ("MUTE".equals(action)) {
            guideMuted = intent.getBooleanExtra("muted", true);
            broadcastStatus();
        } else if ("VOLUME".equals(action)) {
            guideVolume = Math.max(0f, Math.min(1f, intent.getFloatExtra("volume", 1f)));
            broadcastStatus();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startServer(boolean mic, String path) {
        try {
            stopServerOnly();
            File file = path == null ? null : new File(path);
            if (!mic && (file == null || !file.exists())) return;
            port = findFreePort(8080);
            server = new LiveServer(port, mic, file);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            micActive = mic;
            acquireMulticastLock();
            registerNsd();
            broadcastStatus();
        } catch (Exception e) {
            stopEverything();
        }
    }

    private int findFreePort(int preferred) {
        for (int p = preferred; p < preferred + 20; p++) {
            try (ServerSocket s = new ServerSocket(p)) { return p; } catch (IOException ignored) {}
        }
        return preferred;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
            if (multicastLock == null) multicastLock = wm.createMulticastLock("mhtour-nsd");
            multicastLock.setReferenceCounted(false);
            if (!multicastLock.isHeld()) multicastLock.acquire();
        } catch (Exception ignored) {}
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("MH-Tour-Guide");
            info.setServiceType("_mhtour._tcp.");
            info.setPort(port);
            info.setAttribute("code", code);
            info.setAttribute("mode", micActive ? "mic" : "audio");
            registration = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo x) {}
                public void onRegistrationFailed(NsdServiceInfo x, int e) {}
                public void onServiceUnregistered(NsdServiceInfo x) {}
                public void onUnregistrationFailed(NsdServiceInfo x, int e) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception ignored) {}
    }

    private void unregisterNsd() {
        try { if (nsd != null && registration != null) nsd.unregisterService(registration); } catch (Exception ignored) {}
        registration = null;
    }

    private void stopServerOnly() {
        unregisterNsd();
        try { if (server != null) server.stop(); } catch (Exception ignored) {}
        server = null;
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception ignored) {}
    }

    private void stopEverything() {
        stopServerOnly();
        micActive = false;
        broadcastStatus();
    }

    public boolean isGuideMuted() { return guideMuted; }

    private void broadcastStatus() {
        Intent i = new Intent("com.mhtour.STATUS");
        i.setPackage(getPackageName());
        i.putExtra("active", server != null);
        i.putExtra("mic", micActive);
        i.putExtra("code", code);
        i.putExtra("port", port);
        i.putExtra("clients", server == null ? 0 : server.clientCount());
        sendBroadcast(i);
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel("mhtour", "MH Tour Guide", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void startForegroundNow(String text) {
        Intent n = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, n, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, "mhtour")
                .setContentTitle("MH Tour • Guide")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true);
        startForeground(40, b.build());
    }

    @Override public void onDestroy() { stopEverything(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private final class LiveServer extends NanoHTTPD {
        private final boolean micMode;
        private final File audioFile;
        private final CopyOnWriteArrayList<ClientPipe> clients = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, Long> clientHeartbeats = new ConcurrentHashMap<>();
        int activeClients() { long now=System.currentTimeMillis(); clientHeartbeats.entrySet().removeIf(e -> now-e.getValue() > 15000); return clientHeartbeats.size() + clients.size(); }
        private AudioRecord recorder;
        private MediaCodec encoder;
        private Thread captureThread;
        private volatile boolean running;

        LiveServer(int port, boolean micMode, File file) { super(port); this.micMode = micMode; this.audioFile = file; }
        int clientCount() { return clients.size(); }

        @Override public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if ("/status".equals(uri)) {
                String body = "{\"code\":\"" + code + "\",\"mic\":" + micMode + ",\"clients\":" + activeClients() + ",\"muted\":" + guideMuted + ",\"volume\":" + guideVolume + "}";
                return newFixedLengthResponse(Response.Status.OK, "application/json", body);
            }
            if ("/control".equals(uri)) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"muted\":" + guideMuted + ",\"volume\":" + guideVolume + "}");
            }
            if ("/join".equals(uri)) {
                String id = session.getParms().get("client");
                if (id != null && !id.isEmpty()) clientHeartbeats.put(id, System.currentTimeMillis());
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
            }
            if ("/heartbeat".equals(uri)) {
                String id = session.getParms().get("client");
                if (id != null && !id.isEmpty()) clientHeartbeats.put(id, System.currentTimeMillis());
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
            }
            if ("/leave".equals(uri)) {
                String id = session.getParms().get("client");
                if (id != null) clientHeartbeats.remove(id);
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
            }
            if ("/audio.mp3".equals(uri) && !micMode && audioFile != null) {
                try { return newChunkedResponse(Response.Status.OK, "audio/mpeg", new FileInputStream(audioFile)); }
                catch (Exception e) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "audio error"); }
            }
            if ("/live.aac".equals(uri) && micMode) {
                ClientPipe pipe = new ClientPipe();
                clients.add(pipe);
                ensureMic();
                Response r = newChunkedResponse(Response.Status.OK, "audio/aac", pipe.in);
                r.addHeader("Cache-Control", "no-cache, no-store");
                r.addHeader("Connection", "keep-alive");
                r.addHeader("icy-br", "64");
                return r;
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        }

        private synchronized void ensureMic() {
            if (running || !micMode) return;
            if (ContextCompat.checkSelfPermission(GuideStreamService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
            try {
                int sampleRate = 44100;
                int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
                encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1);
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
                if (Build.VERSION.SDK_INT >= 21) fmt.setInteger(MediaFormat.KEY_IS_ADTS, 1);
                encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start(); recorder.startRecording(); running = true;
                captureThread = new Thread(() -> captureLoop(), "MH-Tour-Mic"); captureThread.start();
            } catch (Exception e) { running = false; }
        }

        private void captureLoop() {
            byte[] pcm = new byte[4096];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running) {
                    int n = recorder.read(pcm, 0, pcm.length);
                    if (n > 0) {
                        int inIndex = encoder.dequeueInputBuffer(10000);
                        if (inIndex >= 0) {
                            ByteBuffer ib = encoder.getInputBuffer(inIndex);
                            if (ib != null) { ib.clear(); ib.put(pcm, 0, n); encoder.queueInputBuffer(inIndex, 0, n, System.nanoTime()/1000, 0); }
                        }
                    }
                    int outIndex;
                    while ((outIndex = encoder.dequeueOutputBuffer(info, 0)) >= 0) {
                        ByteBuffer ob = encoder.getOutputBuffer(outIndex);
                        if (ob != null && info.size > 0) {
                            byte[] packet = new byte[info.size]; ob.position(info.offset); ob.limit(info.offset + info.size); ob.get(packet);
                            if (!guideMuted) for (ClientPipe c : clients) c.write(packet);
                        }
                        encoder.releaseOutputBuffer(outIndex, false);
                    }
                }
            } catch (Exception ignored) {} finally { stopMic(); }
        }

        private synchronized void stopMic() {
            running = false;
            try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
            try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
            recorder = null; encoder = null;
            for (ClientPipe c : clients) c.close();
            clients.clear();
        }

        @Override public void stop() {
            stopMic();
            super.stop();
        }

        final class ClientPipe {
            final PipedInputStream in = new PipedInputStream(64 * 1024);
            final PipedOutputStream out;
            ClientPipe() { PipedOutputStream x = null; try { x = new PipedOutputStream(in); } catch (IOException ignored) {} out = x; }
            void write(byte[] data) { try { if (out != null) out.write(data); } catch (IOException e) { close(); clients.remove(this); } }
            void close() { try { in.close(); } catch (Exception ignored) {} try { if (out != null) out.close(); } catch (Exception ignored) {} }
        }
    }
}
