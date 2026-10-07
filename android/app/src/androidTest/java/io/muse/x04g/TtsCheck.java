package io.muse.x04g;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.os.*;
import java.util.Arrays;
import org.json.JSONObject;

// One on-device check, using the app's actual HTTP, parser, playback and stop.
public final class TtsCheck extends Instrumentation {
    boolean edgeGlowCheck,enableEdgeGlow;
    @Override public void onCreate(Bundle args){super.onCreate(args);edgeGlowCheck=args!=null&&"edge-glow".equals(args.getString("check"));enableEdgeGlow=args!=null&&"true".equals(args.getString("enable"));start();}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    MuseService service;
    interface Condition {boolean ready();}
    void await(Condition condition,int seconds,String message)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+seconds*1000L;
        while(SystemClock.elapsedRealtime()<deadline){boolean[] ok={false};runOnMainSync(()->ok[0]=condition.ready());if(ok[0])return;Thread.sleep(50);}
        throw new AssertionError(message);
    }
    void reply(String id,String text){service.speech.update(new MuseLink.Reply(id,text,true));waitForIdleSync();}
    void checkEdgeGlow() {
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;boolean preview=false,wasEnabled=true;
        try {
            float near=AvatarView.glowOpacity(400,6,800,480,64),middle=AvatarView.glowOpacity(400,36,800,480,64),far=AvatarView.glowOpacity(400,100,800,480,64);
            require(near>middle&&middle>far&&far>0,"inward fade must be continuous");
            require(AvatarView.glowOpacity(400,240,800,480,64)<0.001f,"keep screen center clear");
            require(AvatarView.glowOpacity(5,5,800,480,64)==0,"respect rounded physical corners");
            require(AvatarView.glowOpacity(20,20,800,480,24)>AvatarView.glowOpacity(20,20,800,480,120),"corner calibration changes contour");
            MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->MuseService.instance!=null&&MuseService.instance.speech!=null,10,"service startup");service=MuseService.instance;
            await(()->service.state.equals("IDLE")&&service.caption.isEmpty()&&service.link!=null&&service.link.registered&&!service.link.turn&&!service.speech.busy()&&service.voice.record==null,30,"wait for idle before visual preview");
            wasEnabled=enableEdgeGlow||service.store.prefs.getBoolean("edge_glow",true);preview=true;
            runOnMainSync(()->{service.store.prefs.edit().putBoolean("edge_glow",true).apply();service.setState("LISTENING");service.level=0.6f;});
            await(()->activity.avatar.glowAlpha>0.9f&&activity.avatar.glowShader!=null,3,"glow appeared");
            Bitmap image=getUiAutomation().takeScreenshot();require(image!=null,"hardware-rendered preview");
            int edge=image.getPixel(image.getWidth()/2,6);require((edge&0xffffff)!=0,"shader visible on actual display");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"edge-glow-preview.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            runOnMainSync(()->{service.level=0;service.setState("IDLE");});Thread.sleep(1600);
            await(()->activity.avatar.glowAlpha<0.03f,3,"glow faded out");
            result.putString("stream","PASS: rounded corners, inward fade, hardware preview, fade-out; no microphone or TTS");code=Activity.RESULT_OK;
        }catch(Throwable error){result.putString("stream","FAIL: "+error.getMessage());}
        finally {if(preview){boolean restore=wasEnabled;runOnMainSync(()->{service.store.prefs.edit().putBoolean("edge_glow",restore).apply();if(service.voice.record==null&&!service.speech.busy()&&!service.link.turn){service.level=0;service.setState("IDLE");}});}finish(code,result);}
    }
    @Override public void onStart() {
        if(edgeGlowCheck){checkEdgeGlow();return;}
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try {
            JSONObject body=Speech.request("测试。");
            require(body.getString("model").equals("gemini-3.8-flash-tts")&&!body.getBoolean("store")&&body.getBoolean("stream"),"request model/privacy/stream");
            require(body.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice").equals("Leda"),"voice");
            require(Arrays.equals(Speech.audio(new JSONObject("{\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AAABAA==\",\"mime_type\":\"audio/l16\",\"sample_rate\":24000,\"channels\":1}}")),new byte[]{0,0,1,0}),"PCM delta");
            require(Speech.audio(new JSONObject("{\"event_type\":\"step.stop\",\"step\":{\"type\":\"model_output\"}}"))==null,"do not replay final output");
            boolean rejected=false;try{Speech.audio(new JSONObject("{\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AA==\"}}"));}catch(Exception expected){rejected=true;}require(rejected,"reject incomplete PCM sample");
            startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->MuseService.instance!=null&&MuseService.instance.speech!=null,10,"service startup");service=MuseService.instance;
            require(!service.speech.key.isEmpty(),"Run setup-gemini-key first; rebuild APK");
            await(()->service.link!=null&&service.link.registered&&!service.link.turn,30,"Muse connection before TTS check");
            runOnMainSync(()->service.speech.stop());
            String text="你好，这是莉达语音测试。";
            reply("tts-check",text);
            require(service.caption.equals(text),"subtitle before audio");
            reply("tts-check",text);
            runOnMainSync(()->require(service.speech.pending.isEmpty(),"duplicate snapshot requested again"));
            await(()->service.state.equals("SPEAKING")||service.speech.failed,45,"first audio timeout");
            runOnMainSync(()->{require(!service.speech.failed,service.audioStatus);synchronized(service.speech.playback){require(service.speech.track!=null&&service.speech.track.getSampleRate()==24000&&service.speech.track.getChannelCount()==1&&service.speech.track.getAudioFormat()==AudioFormat.ENCODING_PCM_16BIT,"actual AudioTrack format");}});
            await(()->!service.speech.busy(),30,"playback drain timeout");
            require(!service.caption.isEmpty(),"caption should remain after playback");
            await(()->service.caption.isEmpty(),6,"caption should clear after four seconds");
            runOnMainSync(()->service.speech.stop());
            reply("tts-cancel","这是一段较长的语音，用来确认开始下一次讲话时，当前声音会立即停止，已经取消的请求不会重新播放。");
            await(()->service.state.equals("SPEAKING")||service.speech.failed,45,"cancel test first audio timeout");
            runOnMainSync(()->{
                require(!service.speech.failed,service.audioStatus);
                synchronized(service.speech.playback){okhttp3.Call call=service.speech.call;service.speech.stop();require(call==null||call.isCanceled(),"pending HTTP not cancelled");}
                require(!service.speech.busy()&&service.caption.isEmpty(),"stop did not clear turn");service.setState("IDLE");
            });
            Thread.sleep(1000);runOnMainSync(()->require(!service.speech.busy()&&service.caption.isEmpty(),"cancelled callback restored playback"));
            result.putString("stream","PASS: Leda PCM playback, dedup, subtitles, cancellation; no audio files");code=Activity.RESULT_OK;
        }catch(Throwable error){result.putString("stream","FAIL: "+error.getMessage());}
        finally {if(service!=null&&service.speech!=null)runOnMainSync(()->{service.speech.stop();if(service.link!=null&&service.link.registered)service.setState("IDLE");});finish(code,result);}
    }
}
