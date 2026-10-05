package io.muse.x04g;

import android.app.*;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.*;
import java.util.Arrays;
import org.json.JSONObject;

// One on-device check, using the app's actual HTTP, parser, playback and stop.
public final class TtsCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    MuseService service;
    interface Condition {boolean ready();}
    void await(Condition condition,int seconds,String message)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+seconds*1000L;
        while(SystemClock.elapsedRealtime()<deadline){boolean[] ok={false};runOnMainSync(()->ok[0]=condition.ready());if(ok[0])return;Thread.sleep(50);}
        throw new AssertionError(message);
    }
    void reply(String id,String text){service.speech.update(new MuseLink.Reply(id,text,true));waitForIdleSync();}
    @Override public void onStart() {
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
