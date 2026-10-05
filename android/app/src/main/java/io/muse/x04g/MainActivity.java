package io.muse.x04g;

import android.app.*;
import android.content.Intent;
import android.hardware.*;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;

public final class MainActivity extends Activity implements SensorEventListener {
    final Handler ui=new Handler(Looper.getMainLooper());
    boolean resumed;AvatarView avatar;SensorManager sensors;float lux=-1;
    long bothSince;boolean plus,minus;
    final Runnable escape=new Runnable(){public void run(){if(plus&&minus){leave();} }};
    final Runnable heartbeat=new Runnable(){public void run(){if(!resumed)return;try{write("ui-heartbeat",Long.toString(SystemClock.elapsedRealtime()));}catch(Exception ignored){}ui.postDelayed(this,2000);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setVolumeControlStream(AudioManager.STREAM_MUSIC);
        startForegroundService(new Intent(this,MuseService.class));avatar=new AvatarView(this,this::settings);setContentView(avatar);
        sensors=getSystemService(SensorManager.class);Sensor light=sensors.getDefaultSensor(Sensor.TYPE_LIGHT);if(light!=null)sensors.registerListener(this,light,SensorManager.SENSOR_DELAY_NORMAL);
        brightness();
    }
    void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);}
    @Override public void onResume(){super.onResume();resumed=true;new File(getFilesDir(),"maintenance").delete();immersive();ui.post(heartbeat);}
    @Override public void onPause(){resumed=false;ui.removeCallbacks(heartbeat);super.onPause();}
    @Override public void onDestroy(){sensors.unregisterListener(this);ui.removeCallbacks(escape);super.onDestroy();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)immersive();}
    void write(String name,String value)throws Exception{Store.write(new File(getFilesDir(),name),value);}
    void maintenance(){try{write("maintenance","1");}catch(Exception e){Toast.makeText(this,"Maintenance marker failed",Toast.LENGTH_SHORT).show();}}
    void leave(){maintenance();Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(home);finish();}
    void confirm(String title,Runnable action){new AlertDialog.Builder(this).setTitle(title).setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->action.run()).show();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        MuseService s=MuseService.instance;int key=e.getKeyCode();boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
        if(key==KeyEvent.KEYCODE_VOLUME_UP||key==KeyEvent.KEYCODE_VOLUME_DOWN){
            if(key==KeyEvent.KEYCODE_VOLUME_UP)plus=down;else minus=down;
            if(plus&&minus&&bothSince==0){bothSince=SystemClock.elapsedRealtime();ui.postDelayed(escape,5000);}else if(!plus||!minus){bothSince=0;ui.removeCallbacks(escape);}
            if(down&&e.getRepeatCount()==0){AudioManager audio=getSystemService(AudioManager.class);int step=Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/20);audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.max(0,Math.min(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),audio.getStreamVolume(AudioManager.STREAM_MUSIC)+(key==KeyEvent.KEYCODE_VOLUME_UP?step:-step))),0);}return true;
        }
        if(s!=null&&(key==KeyEvent.KEYCODE_MUTE||key==KeyEvent.KEYCODE_F10)){if(e.getRepeatCount()==0){android.util.Log.i("MuseX04G","PTT action="+e.getAction()+" key="+key+" scan="+e.getScanCode());s.ptt(down);}return true;}
        return super.dispatchKeyEvent(e);
    }
    void brightness(){android.content.SharedPreferences p=getSharedPreferences("muse",MODE_PRIVATE);WindowManager.LayoutParams w=getWindow().getAttributes();w.screenBrightness=p.getBoolean("auto_brightness",true)?-1:p.getFloat("brightness",0.5f);getWindow().setAttributes(w);}
    @Override public void onSensorChanged(SensorEvent e){lux=e.values[0];}
    @Override public void onAccuracyChanged(Sensor s,int accuracy){}
    void button(LinearLayout page,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());page.addView(b);}
    TextView info(LinearLayout page,String value){TextView v=new TextView(this);v.setTextSize(14);v.setText(value);page.addView(v);return v;}
    void settings(){
        MuseService s=MuseService.instance;if(s==null||s.store==null)return;
        LinearLayout page=new LinearLayout(this);page.setPadding(18,8,18,8);page.setOrientation(LinearLayout.VERTICAL);
        String paired="unknown";try{paired=s.store.pairing()==null?"Unpaired":"Paired";}catch(Exception ignored){}
        info(page,"Muse: "+paired+" · "+s.status+"\n"+s.store.device+"\nVM: "+(s.link==null?"":s.link.vmName));
        button(page,"Reconnect",()->s.link.reconnect());button(page,"Open pairing",s::pair);
        button(page,"Wi-Fi Settings",()->{maintenance();try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}});
        TextView light=info(page,"Display · Light sensor: "+(lux<0?"unavailable":lux+" lux"));
        CheckBox auto=new CheckBox(this);auto.setText("Auto Brightness");auto.setChecked(s.store.prefs.getBoolean("auto_brightness",true));auto.setOnCheckedChangeListener((b,on)->{s.store.prefs.edit().putBoolean("auto_brightness",on).apply();brightness();});page.addView(auto);
        SeekBar bright=new SeekBar(this);bright.setMax(95);bright.setProgress(Math.round(s.store.prefs.getFloat("brightness",0.5f)*100)-5);bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean user){if(user){s.store.prefs.edit().putFloat("brightness",(p+5)/100f).apply();brightness();}}});page.addView(bright);
        AudioManager audio=getSystemService(AudioManager.class);info(page,"Audio · volume "+audio.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)+"\nMic muted="+audio.isMicrophoneMute()+" · speaker: "+s.audioStatus);
        TextView gainLabel=info(page,"Mic gain ×"+s.store.prefs.getFloat("mic_gain",5));SeekBar gain=new SeekBar(this);gain.setMax(15);gain.setProgress(Math.round(s.store.prefs.getFloat("mic_gain",5))-1);gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean user){if(user){s.store.prefs.edit().putFloat("mic_gain",p+1).apply();gainLabel.setText("Mic gain ×"+(p+1));}}});page.addView(gain);
        info(page,"Device · app "+Store.VERSION+"\nBuild: "+Build.DISPLAY+" · Android "+Build.VERSION.RELEASE+"\nSDK: 3229892e93c1 · IP "+ip()+"\nUptime: "+SystemClock.elapsedRealtime()/1000+"s");
        button(page,"Restart Muse service",()->{s.turnGeneration++;s.voice.stop();s.speech.stop();stopService(new Intent(this,MuseService.class));ui.postDelayed(()->startForegroundService(new Intent(this,MuseService.class)),400);});
        button(page,"Restart UI",this::recreate);
        button(page,"Reboot device",()->confirm("重启设备？",()->{try{write("maintenance-request","reboot");}catch(Exception e){Toast.makeText(this,"Request failed",Toast.LENGTH_SHORT).show();}}));
        button(page,"Return to Android",()->confirm("退出 Muse 全屏？",this::leave));
        ScrollView scroll=new ScrollView(this);scroll.addView(page);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Muse Settings").setView(scroll).setNegativeButton("关闭",null).create();
        final Runnable sensorRefresh=new Runnable(){public void run(){if(dialog.isShowing()){light.setText("Display · Light sensor: "+(lux<0?"unavailable":lux+" lux"));ui.postDelayed(this,1000);}}};dialog.setOnDismissListener(d->{ui.removeCallbacks(sensorRefresh);immersive();});dialog.show();ui.post(sensorRefresh);
    }
    String ip(){try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(n.getInetAddresses()))if(!a.isLoopbackAddress()&&a instanceof Inet4Address)return a.getHostAddress();}catch(Exception ignored){}return "offline";}
}
