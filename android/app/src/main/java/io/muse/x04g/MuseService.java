package io.muse.x04g;

import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.hardware.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public final class MuseService extends Service {
    static volatile MuseService instance;
    volatile String status="BOOT",caption="";
    volatile String state="BOOT",audioStatus="";
    volatile int captionOffset;
    volatile float level;
    volatile long turnGeneration,modeStarted=SystemClock.elapsedRealtime();
    final Handler main=new Handler(Looper.getMainLooper());
    Speech speech;Voice voice;
    Store store;MuseLink link;BleSetup ble;
    @Override public void onCreate() {
        super.onCreate();instance=this;
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("muse","Muse service",NotificationManager.IMPORTANCE_LOW));
        startForeground(1,new Notification.Builder(this,"muse").setContentTitle("Muse X04G").setContentText("Muse device service").setSmallIcon(android.R.drawable.ic_btn_speak_now).build());
        try {
            store=new Store(this);
            ByteArrayOutputStream fixture=new ByteArrayOutputStream();
            try(InputStream in=getAssets().open("link_pairing_v5.json")){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)fixture.write(b,0,n);}
            JSONObject vectors=new JSONObject(new String(fixture.toByteArray(),StandardCharsets.UTF_8));
            for(int i=0;i<vectors.getJSONArray("vectors").length();i++)Pairing.selfCheck(vectors.getJSONArray("vectors").getJSONObject(i));Log.i("MuseX04G","official pairing v5 fixtures passed");
            BluetoothAdapter bt=getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
            AudioManager audio=getSystemService(AudioManager.class);SensorManager sensors=getSystemService(SensorManager.class);
            String hardware="BLE advertise="+(bt!=null&&bt.isMultipleAdvertisementSupported())+"; light="+(sensors.getDefaultSensor(Sensor.TYPE_LIGHT)!=null)+"; mic muted="+audio.isMicrophoneMute()+"; volume="+audio.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_ALL))Log.i("MuseX04G","audio device type="+d.getType()+" input="+d.isSource()+" rates="+java.util.Arrays.toString(d.getSampleRates()));
            Log.i("MuseX04G",hardware);
            String old=store.prefs.getString("bluetooth_name",null);if(old!=null&&bt!=null&&store.name.equals(bt.getName()))bt.setName(old);
            speech=new Speech(this);voice=new Voice(this);
            link=new MuseLink(store,this::linkStatus,reply->{if(reply.id.isEmpty())main.post(()->{caption="";});else speech.update(reply);});link.start();
            link.worker.scheduleAtFixedRate(()->{try{Store.write(new java.io.File(getFilesDir(),"service-heartbeat"),Long.toString(SystemClock.elapsedRealtime()));}catch(Exception ignored){}},0,5,java.util.concurrent.TimeUnit.SECONDS);
        }catch(Exception e){status="ERROR: "+e.getClass().getSimpleName();setState("ERROR");Log.e("MuseX04G",status);}
    }
    void pair() {if(link==null)return;if(ble!=null)ble.close();ble=new BleSetup(store,link,s->status=s);ble.start();}
    void setState(String next){if(!state.equals(next)){Log.i("MuseX04G","state "+state+" -> "+next);state=next;modeStarted=SystemClock.elapsedRealtime();}}
    void linkStatus(String next){main.post(()->{
        status=next;
        if(next.startsWith("Connected:")){if(voice.record==null&&!speech.busy()){setState("IDLE");hideCaptionLater();}}
        else if(next.equals("Thinking")){if(!speech.busy())setState("THINKING");}
        else if(next.equals("Unpaired")||next.startsWith("Unpaired by")||(next.contains("failed")&&!link.registered)||next.contains("lost")||next.contains("offline")||(next.contains("timeout")&&!link.registered)||next.startsWith("Link closed")){turnGeneration++;voice.stop();speech.stop();setState("OFFLINE");}
        else if(next.contains("timeout")){speech.stop();setState("ERROR");main.postDelayed(()->{if(link.registered&&!link.turn)setState("IDLE");},4000);}
    });}
    void ptt(boolean down){
        if(down){if(voice.record!=null)return;speech.stop();turnGeneration++;caption="";
            if(link==null||!link.registered){status="Muse not connected";setState("OFFLINE");return;}
            try {getSystemService(AudioManager.class).setMicrophoneMute(false);voice.start(turnGeneration);setState("LISTENING");}
            catch(Exception e){voice.stop();setState("ERROR");status="Microphone error: "+e.getClass().getSimpleName();}
        }else if(voice.record!=null){voice.stop();setState("THINKING");}
    }
    void hideCaptionLater(){long expected=turnGeneration;main.postDelayed(()->{if(expected==turnGeneration&&!speech.busy()&&voice.record==null&&(link==null||!link.turn)){caption="";}},4000);}
    void avatarWhenReady(int tries){if(link==null)return;if(link.registered){link.requestAvatar();return;}if(tries>0)main.postDelayed(()->avatarWhenReady(tries-1),1000);}
    @Override public int onStartCommand(Intent intent,int flags,int id) {if(intent!=null&&"io.muse.x04g.AVATAR_REQUEST".equals(intent.getAction())){new java.io.File(getCacheDir(),"avatar-candidate.c").delete();new java.io.File(getCacheDir(),"avatar-result").delete();avatarWhenReady(45);}return START_STICKY;}
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy(){instance=null;turnGeneration++;main.removeCallbacksAndMessages(null);if(voice!=null)voice.stop();if(speech!=null)speech.close();if(ble!=null)ble.close();if(link!=null)link.close();super.onDestroy();}
}
