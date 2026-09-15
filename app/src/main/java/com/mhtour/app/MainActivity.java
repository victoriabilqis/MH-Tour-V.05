package com.mhtour.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.net.nsd.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    EditText code, audio; TextView status, clientCount, volumeLabel; Button scan, connect, start, show, play, stop, select, mic, muteGuide; SeekBar guideVolume;
    ExoPlayer player; String streamUrl; NsdManager nsd; NsdManager.DiscoveryListener discovery;
    boolean guideMode = true;
    boolean guideMuted = false;
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    final String clientId = UUID.randomUUID().toString();
    Runnable controlPoll;

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> qrScanner =
        registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) connectFromQr(result.getContents());
        });

    @Override public void onCreate(Bundle b) {
        super.onCreate(b); setContentView(R.layout.activity_main);
        code=findViewById(R.id.groupCode); audio=findViewById(R.id.audioPath); status=findViewById(R.id.status);
        scan=findViewById(R.id.scanButton); connect=findViewById(R.id.connectButton); start=findViewById(R.id.startGuide);
        show=findViewById(R.id.showQr); play=findViewById(R.id.playButton); stop=findViewById(R.id.stopButton);
        select=findViewById(R.id.selectAudio); mic=findViewById(R.id.micButton);
        muteGuide=findViewById(R.id.muteGuide); guideVolume=findViewById(R.id.guideVolume); clientCount=findViewById(R.id.clientCount); volumeLabel=findViewById(R.id.volumeLabel);
        findViewById(R.id.guideMode).setOnClickListener(v -> setMode(true));
        findViewById(R.id.jemaahMode).setOnClickListener(v -> setMode(false));
        select.setOnClickListener(v -> pickAudio()); start.setOnClickListener(v -> startGuide());
        show.setOnClickListener(v -> showQr()); scan.setOnClickListener(v -> scanQr()); connect.setOnClickListener(v -> discoverByCode());
        play.setOnClickListener(v -> play()); stop.setOnClickListener(v -> stop());
        mic.setOnClickListener(v -> requestGuideMic());
        muteGuide.setOnClickListener(v -> setGuideMute(!guideMuted));
        guideVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar b,int p,boolean f){ volumeLabel.setText("Volume Jemaah: "+p+"%"); if(f&&guideMode) setGuideVolume(p/100f); } public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} });
        setMode(true); handleIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},55);
    }

    void setMode(boolean guide) {
        guideMode=guide;
        select.setVisibility(guide?View.VISIBLE:View.GONE); start.setVisibility(guide?View.VISIBLE:View.GONE); show.setVisibility(guide?View.VISIBLE:View.GONE); mic.setVisibility(guide?View.VISIBLE:View.GONE); muteGuide.setVisibility(guide?View.VISIBLE:View.GONE); guideVolume.setVisibility(guide?View.VISIBLE:View.GONE); volumeLabel.setVisibility(guide?View.VISIBLE:View.GONE); clientCount.setVisibility(guide?View.VISIBLE:View.GONE);
        scan.setVisibility(guide?View.GONE:View.VISIBLE); connect.setVisibility(guide?View.GONE:View.VISIBLE); play.setVisibility(guide?View.GONE:View.VISIBLE); stop.setVisibility(guide?View.GONE:View.VISIBLE);
        audio.setVisibility(guide?View.VISIBLE:View.GONE);
        if(guide) status.setText("🎙 MODE GUIDE AKTIF • MIC hanya untuk Guide"); else status.setText("👂 MODE JEMAAH AKTIF • HANYA PENDENGAR • Scan QR atau masukkan kode");
    }

    void pickAudio(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("audio/mpeg"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,10); }
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d); if(r==10&&c==RESULT_OK&&d!=null){try{getContentResolver().takePersistableUriPermission(d.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){} audio.setText(d.getData().toString()); status.setText("Audio MP3 siap disiarkan.");}}

    void startGuide(){
        if(audio.getText().length()==0){toast("Pilih MP3 terlebih dahulu");return;}
        String c=code.getText().toString().trim().toUpperCase(Locale.ROOT);
        if(c.isEmpty()){c="MHTOUR-"+(1000+new Random().nextInt(9000)); code.setText(c);}
        try{
            File f=new File(getCacheDir(),"guide.mp3");
            try(InputStream in=getContentResolver().openInputStream(Uri.parse(audio.getText().toString())); OutputStream out=new FileOutputStream(f)){
                byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0) out.write(buf,0,n);
            }
            Intent i=new Intent(this,GuideStreamService.class).setAction(GuideStreamService.ACTION_START).putExtra("path",f.getAbsolutePath()).putExtra("code",c);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            streamUrl="http://"+ip()+":"+getGuidePort()+"/audio.mp3";
            status.setText("🟢 SIARAN GUIDE AKTIF\nKode: "+c+"\nJemaah: Scan QR atau input kode");
        }catch(Exception e){toast("Gagal menyiapkan audio: "+e.getMessage());}
    }

    void requestGuideMic(){
        if(!guideMode){toast("MIC hanya tersedia pada mode Guide.");return;}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.RECORD_AUDIO},77);
            return;
        }
        String c=code.getText().toString().trim().toUpperCase(Locale.ROOT);
        if(c.isEmpty()){c="MHTOUR-"+(1000+new Random().nextInt(9000)); code.setText(c);}
        Intent i=new Intent(this,GuideStreamService.class).setAction(GuideStreamService.ACTION_MIC_START).putExtra(GuideStreamService.EXTRA_CODE,c);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        streamUrl="http://"+ip()+":"+getGuidePort()+"/live.aac";
        status.setText("🎙 SIARAN MIC GUIDE LIVE AKTIF\nKode: "+c+"\nJemaah: Scan QR atau input kode\nMIC hanya tersedia di perangkat Guide");
    }

    int getGuidePort(){ return 8080; }

    void scanQr(){ if(guideMode){toast("Scan QR hanya digunakan oleh Jemaah.");return;} ScanOptions o=new ScanOptions(); o.setDesiredBarcodeFormats(ScanOptions.QR_CODE); o.setPrompt("Scan QR Koneksi dari Guide MH Tour"); o.setBeepEnabled(true); o.setOrientationLocked(false); qrScanner.launch(o); }
    void connectFromQr(String raw){
        try{
            Uri u=Uri.parse(raw.trim()); String url=null, c=null;
            if("mhtour".equalsIgnoreCase(u.getScheme()) && "join".equalsIgnoreCase(u.getHost())){url=u.getQueryParameter("url"); c=u.getQueryParameter("code");}
            else if(raw.startsWith("http://")||raw.startsWith("https://")) url=raw.trim();
            if(url==null||url.isEmpty()) throw new Exception("QR invalid");
            streamUrl=url; if(c!=null) code.setText(c); saveCode(c,url); status.setText("QR terbaca • Menghubungkan ke streaming..."); play();
        }catch(Exception e){toast("QR MH Tour tidak valid");}
    }

    void discoverByCode(){
        if(guideMode){toast("Input kode koneksi hanya digunakan oleh Jemaah.");return;}
        String wanted=code.getText().toString().trim().toUpperCase(Locale.ROOT);
        if(wanted.isEmpty()){toast("Masukkan kode grup terlebih dahulu");return;}
        status.setText("🔎 Mencari Guide dengan kode "+wanted+" di Wi‑Fi...");
        if(nsd==null) nsd=(NsdManager)getSystemService(NSD_SERVICE);
        stopDiscovery();
        discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String s){}
            public void onServiceFound(NsdServiceInfo info){
                if("_mhtour._tcp.".equals(info.getServiceType())) nsd.resolveService(info,new NsdManager.ResolveListener(){
                    public void onResolveFailed(NsdServiceInfo x,int e){}
                    public void onServiceResolved(NsdServiceInfo x){
                        String found=x.getAttributes().get("code")!=null?new String(x.getAttributes().get("code")):"";
                        if(wanted.equalsIgnoreCase(found)){
                            String mode=x.getAttributes().get("mode")!=null?new String(x.getAttributes().get("mode")):"audio"; String path="mic".equalsIgnoreCase(mode)?"/live.aac":"/audio.mp3"; String u="http://"+x.getHost().getHostAddress()+":"+x.getPort()+path;
                            runOnUiThread(()->{streamUrl=u;saveCode(wanted,u);status.setText("🟢 Guide ditemukan • "+wanted+"\n▶ Terhubung ke streaming"); stopDiscovery(); play();});
                        }
                    }
                });
            }
            public void onServiceLost(NsdServiceInfo i){} public void onDiscoveryStopped(String s){}
            public void onStartDiscoveryFailed(String s,int e){runOnUiThread(()->status.setText("Gagal mencari Guide di Wi‑Fi."));}
            public void onStopDiscoveryFailed(String s,int e){}
        };
        nsd.discoverServices("_mhtour._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
        new Handler(Looper.getMainLooper()).postDelayed(()->{if(streamUrl==null&&discovery!=null) status.setText("Guide dengan kode "+wanted+" belum ditemukan. Pastikan satu Wi‑Fi/hotspot.");},9000);
    }

    void saveCode(String c,String u){if(c!=null&&!c.isEmpty())getPreferences(0).edit().putString("code_"+c.toUpperCase(Locale.ROOT),u).apply();}
    String ip(){try{Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();while(ns.hasMoreElements()){Enumeration<InetAddress>a=ns.nextElement().getInetAddresses();while(a.hasMoreElements()){InetAddress x=a.nextElement();if(!x.isLoopbackAddress()&&x instanceof Inet4Address)return x.getHostAddress();}}}catch(Exception ignored){}return "127.0.0.1";}

    void showQr(){
        if(!guideMode){toast("QR koneksi hanya dapat dibuat oleh Guide.");return;}
        if(streamUrl==null)streamUrl="http://"+ip()+":"+getGuidePort()+"/audio.mp3";
        String c=code.getText().toString().trim(); String payload="mhtour://join?code="+Uri.encode(c)+"&url="+Uri.encode(streamUrl);
        try{BitMatrix m=new QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,700,700);Bitmap bm=Bitmap.createBitmap(700,700,Bitmap.Config.RGB_565);for(int x=0;x<700;x++)for(int y=0;y<700;y++)bm.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);ImageView im=new ImageView(this);im.setImageBitmap(bm);new AlertDialog.Builder(this).setTitle("QR MH Tour • "+c).setMessage("Jemaah cukup Scan QR ini. Aplikasi akan langsung terhubung dan memutar streaming.").setView(im).setPositiveButton("Tutup",null).show();}catch(Exception e){toast("Gagal membuat QR");}
    }

    void play(){
        if(guideMode){toast("Mode Guide tidak memiliki tombol dengar Jemaah.");return;}
        if(streamUrl==null||streamUrl.isEmpty()){toast("Scan QR atau hubungkan dengan kode");return;}
        if(player!=null)player.release();
        joinGuide();
        player=new ExoPlayer.Builder(this).build();
        player.addListener(new androidx.media3.common.Player.Listener(){
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                status.setText("⚠ Koneksi terputus • mencoba terhubung kembali...");
                mainHandler.postDelayed(() -> { if(player!=null && streamUrl!=null) { player.setMediaItem(MediaItem.fromUri(streamUrl)); player.prepare(); player.play(); } }, 1500);
            }
        });
        player.setMediaItem(MediaItem.fromUri(streamUrl));
        player.prepare();
        player.play();
        startControlPolling();
        status.setText("▶ STREAMING AKTIF\nTerhubung ke Guide\nMIC hanya berasal dari perangkat Guide.");
    }
    void stop(){leaveGuide(); if(player!=null)player.stop(); stopControlPolling(); status.setText("Streaming dihentikan.");}
    void setGuideMute(boolean muted){ if(!guideMode){toast("Kontrol suara Guide hanya tersedia pada mode Guide.");return;} guideMuted=muted; muteGuide.setText(muted?"🔊 AKTIFKAN SUARA JEMAAH":"🔇 MUTE JEMAAH"); Intent i=new Intent(this,GuideStreamService.class).setAction("MUTE").putExtra("muted",muted); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }
    void setGuideVolume(float v){ if(!guideMode)return; Intent i=new Intent(this,GuideStreamService.class).setAction("VOLUME").putExtra("volume",v); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }
    void joinGuide(){ if(streamUrl==null)return; new Thread(() -> { try{ URL u=new URL(streamUrl); URL base=new URL(u.getProtocol(),u.getHost(),u.getPort(),"/join?client="+URLEncoder.encode(clientId,"UTF-8")); HttpURLConnection c=(HttpURLConnection)base.openConnection(); c.setConnectTimeout(2000); c.getInputStream().close(); c.disconnect(); }catch(Exception ignored){} }).start(); }
    void leaveGuide(){ if(streamUrl==null)return; new Thread(() -> { try{ URL u=new URL(streamUrl); URL base=new URL(u.getProtocol(),u.getHost(),u.getPort(),"/leave?client="+URLEncoder.encode(clientId,"UTF-8")); HttpURLConnection c=(HttpURLConnection)base.openConnection(); c.setConnectTimeout(1500); c.getInputStream().close(); c.disconnect(); }catch(Exception ignored){} }).start(); }
    void startControlPolling(){ stopControlPolling(); controlPoll=new Runnable(){ public void run(){ pollControl(); mainHandler.postDelayed(this,2000); } }; mainHandler.post(controlPoll); }
    void stopControlPolling(){ if(controlPoll!=null)mainHandler.removeCallbacks(controlPoll); controlPoll=null; }
    void pollControl(){ if(guideMode||streamUrl==null)return; new Thread(() -> { try{ URL u=new URL(streamUrl); URL base=new URL(u.getProtocol(),u.getHost(),u.getPort(),"/control"); HttpURLConnection c=(HttpURLConnection)base.openConnection(); c.setConnectTimeout(1500); c.setReadTimeout(1500); BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream())); StringBuilder b=new StringBuilder(); String line; while((line=r.readLine())!=null)b.append(line); r.close(); c.disconnect(); String j=b.toString(); boolean muted=j.contains("\"muted\":true"); int vi=j.indexOf("\"volume\":"); float vol=1f; if(vi>=0){int st=vi+10,en=j.indexOf('}',st); try{vol=Float.parseFloat(j.substring(st,en));}catch(Exception ignored){}} final float fv=Math.max(0f,Math.min(1f,vol)); runOnUiThread(() -> {if(player!=null)player.setVolume(muted?0f:fv);}); }catch(Exception ignored){} }); }

    void handleIntent(Intent i){if(i!=null&&Intent.ACTION_VIEW.equals(i.getAction())&&i.getData()!=null)connectFromQr(i.getData().toString());}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleIntent(i);}
    void stopDiscovery(){try{if(nsd!=null&&discovery!=null)nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){} discovery=null;}
    @Override protected void onDestroy(){leaveGuide(); stopControlPolling(); stopDiscovery();if(player!=null)player.release();super.onDestroy();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
