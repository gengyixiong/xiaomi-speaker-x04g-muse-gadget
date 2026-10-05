package io.muse.x04g;

import android.media.*;
import android.os.*;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

// Same update/stop boundary as before; Muse still supplies all reply text.
final class Speech {
    static final String MODEL="gemini-3.8-flash-tts";
    static final int RATE=24000;
    final MuseService service;
    final Handler main=new Handler(Looper.getMainLooper());
    final Map<String,Integer> positions=new HashMap<>();
    final ArrayDeque<String> pending=new ArrayDeque<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build();
    final Object playback=new Object();
    final String key;
    volatile int generation;
    volatile boolean closed;
    Call call;
    AudioTrack track;
    boolean active,failed;

    Speech(MuseService service) {
        this.service=service;
        String value="";
        try(InputStream in=service.getAssets().open("gemini-api-key")) {
            value=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8)).readLine();
            value=value==null?"":value.trim();
        }catch(IOException ignored){}
        key=value;
        service.audioStatus=key.isEmpty()?"Gemini API key not configured":"Gemini TTS ready";
    }

    void update(MuseLink.Reply reply) {int expected=generation;main.post(()->{
        if(!current(expected)||service.state.equals("LISTENING"))return;
        int at=positions.getOrDefault(reply.id,0),end=at;
        String text=reply.text;
        if(at>text.length())return;
        if(!active){service.caption=text;service.captionOffset=0;}
        for(int i=at;i<text.length();i++)if("。！？!?\n；;".indexOf(text.charAt(i))>=0||i-end>=160){enqueue(text.substring(end,i+1));end=i+1;}
        if(reply.complete&&end<text.length()){enqueue(text.substring(end));end=text.length();}
        positions.put(reply.id,end);
        pump();
    });}

    void enqueue(String text){text=text.trim();if(!failed&&!text.isEmpty())pending.add(text);}
    boolean current(int expected){return !closed&&expected==generation;}
    void pump() {
        if(active||failed||closed||pending.isEmpty())return;
        StringBuilder text=new StringBuilder(pending.remove());
        while(!pending.isEmpty()&&text.length()+pending.peek().length()<=320)text.append('\n').append(pending.remove());
        active=true;int expected=generation;
        Log.i("MuseX04G","Gemini TTS request chars="+text.length());
        worker.execute(()->speak(text.toString(),expected));
    }

    static JSONObject request(String text) throws JSONException {
        return new JSONObject().put("model",MODEL)
            .put("input",new JSONArray().put(new JSONObject().put("type","user_input")
                .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)))))
            .put("response_format",new JSONObject().put("type","audio").put("mime_type","audio/l16").put("sample_rate",RATE))
            .put("generation_config",new JSONObject().put("speech_config",new JSONArray().put(new JSONObject().put("voice","Leda"))))
            .put("stream",true).put("store",false);
    }

    // Consume SSE deltas only, never the final repeated model_output payload.
    static byte[] audio(JSONObject event) throws Exception {
        String type=event.optString("event_type");
        String status=event.optString("status");
        JSONObject interaction=event.optJSONObject("interaction");
        if(interaction!=null)status=interaction.optString("status",status);
        if(event.has("error")||type.equals("error")||status.equals("failed")||status.equals("cancelled")||status.equals("incomplete"))throw new IOException("Gemini API stream error");
        if(!type.equals("step.delta"))return null;
        JSONObject delta=event.optJSONObject("delta");
        if(delta==null||!delta.optString("type").equals("audio")||delta.optString("data").isEmpty())return null;
        if(!delta.optString("mime_type","audio/l16").equals("audio/l16")||delta.optInt("sample_rate",RATE)!=RATE||delta.optInt("channels",1)!=1)throw new IOException("Unsupported Gemini audio format");
        byte[] pcm=Base64.decode(delta.getString("data"),Base64.DEFAULT);
        if((pcm.length&1)!=0)throw new IOException("Invalid Gemini PCM");
        return pcm;
    }

    void speak(String text,int expected) {
        AudioTrack output=null;Call requestCall=null;String error=null;
        long frames=0,started=SystemClock.elapsedRealtime(),lastProgress=0;
        try {
            if(!current(expected))return;
            if(key.isEmpty())throw new IOException("Gemini API key not configured");
            Request request=new Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/interactions")
                .header("x-goog-api-key",key).header("Accept","text/event-stream")
                .post(RequestBody.create(request(text).toString(),MediaType.get("application/json"))).build();
            requestCall=client.newCall(request);
            synchronized(playback){if(!current(expected))return;call=requestCall;}
            try(Response response=requestCall.execute()) {
                if(!response.isSuccessful())throw new IOException("Gemini TTS HTTP "+response.code());
                if(response.body()==null||!response.header("Content-Type","").startsWith("text/event-stream"))throw new IOException("Invalid Gemini stream");
                StringBuilder data=new StringBuilder();boolean completed=false;
                while(current(expected)) {
                    String line=response.body().source().readUtf8LineStrict(4*1024*1024);
                    if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).trim());if(data.length()>4*1024*1024)throw new IOException("Gemini audio chunk too large");}
                    if(!line.isEmpty()||data.length()==0)continue;
                    String payload=data.toString();data.setLength(0);
                    if(payload.equals("[DONE]")){completed=true;break;}
                    JSONObject event=new JSONObject(payload);
                    byte[] pcm=audio(event);
                    JSONObject interaction=event.optJSONObject("interaction");
                    String status=interaction==null?event.optString("status"):interaction.optString("status");
                    if(status.equals("completed"))completed=true;
                    if(pcm!=null&&pcm.length>0) {
                        if(output==null) {
                            int minimum=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                            if(minimum<=0)throw new IOException("Speaker unavailable");
                            output=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                                .setBufferSizeInBytes(Math.max(minimum,8192)).setTransferMode(AudioTrack.MODE_STREAM).build();
                            synchronized(playback){if(!current(expected))return;track=output;output.play();}
                            main.post(()->{if(current(expected)){service.setState("SPEAKING");service.audioStatus="Gemini TTS playing";service.caption=text;service.captionOffset=0;}});
                            Log.i("MuseX04G","Gemini TTS first audio ms="+(SystemClock.elapsedRealtime()-started));
                        }
                        for(int offset=0;offset<pcm.length&&current(expected);) {
                            int n=output.write(pcm,offset,pcm.length-offset,AudioTrack.WRITE_NON_BLOCKING);
                            if(n<0)throw new IOException("Speaker playback error");
                            if(n==0)Thread.sleep(10);else{offset+=n;frames+=n/2;}
                            long now=SystemClock.elapsedRealtime();
                            if(now-lastProgress>=100){lastProgress=now;progress(output,text,expected,0);}
                        }
                    }
                    if(completed)break;
                }
                if(!current(expected))return;
                if(!completed)throw new IOException("Incomplete Gemini audio");
                if(output==null||frames==0)throw new IOException("Gemini returned empty audio");
            }
            long deadline=SystemClock.elapsedRealtime()+frames*1000/RATE+3000;
            while(current(expected)&&Integer.toUnsignedLong(output.getPlaybackHeadPosition())<frames) {
                if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Speaker playback timeout");
                progress(output,text,expected,frames);Thread.sleep(50);
            }
            if(current(expected))Log.i("MuseX04G","Gemini TTS played bytes="+frames*2);
        }catch(Exception e) {
            if(current(expected)) {
                error=e instanceof java.net.SocketTimeoutException||e instanceof java.io.InterruptedIOException?"Gemini TTS timed out":
                    e instanceof IOException&&e.getMessage()!=null&&e.getMessage().startsWith("Gemini ")?e.getMessage():"Gemini TTS unavailable";
                Log.w("MuseX04G",error); // No response bodies, reply text, headers or keys.
            }
        }finally {
            synchronized(playback){if(call==requestCall)call=null;if(track==output)track=null;if(output!=null){try{output.pause();output.flush();}catch(IllegalStateException ignored){}output.release();}}
            String result=error;
            main.post(()->finish(expected,result));
        }
    }

    void progress(AudioTrack output,String text,int expected,long totalFrames) {
        long played=Integer.toUnsignedLong(output.getPlaybackHeadPosition());
        // ponytail: no word timestamps in Gemini PCM; approximate subtitle paging.
        double fraction=totalFrames>0?(double)played/totalFrames:played/(double)RATE/Math.max(1,text.codePointCount(0,text.length())/(text.matches("(?s).*[\\p{IsHan}].*")?5.0:13.0));
        int offset=text.offsetByCodePoints(0,(int)(Math.min(.99,fraction)*text.codePointCount(0,text.length())));
        main.post(()->{if(current(expected))service.captionOffset=Math.max(service.captionOffset,offset);});
    }

    void finish(int expected,String error) {
        if(!current(expected))return;
        active=false;
        if(error!=null){failed=true;pending.clear();service.audioStatus=error;Toast.makeText(service,error,Toast.LENGTH_SHORT).show();}
        else service.audioStatus="Gemini TTS ready";
        pump();
        if(!busy()){service.setState(service.link!=null&&service.link.turn?"THINKING":"IDLE");service.hideCaptionLater();}
    }

    void stop() {
        generation++;
        synchronized(playback){if(call!=null)call.cancel();if(track!=null){try{track.pause();track.flush();}catch(IllegalStateException ignored){}}}
        positions.clear();pending.clear();active=false;failed=false;service.caption="";service.captionOffset=0;
        service.audioStatus=key.isEmpty()?"Gemini API key not configured":"Gemini TTS ready";
    }
    boolean busy(){return active||!pending.isEmpty();}
    void close(){stop();closed=true;main.removeCallbacksAndMessages(null);worker.shutdownNow();client.dispatcher().cancelAll();client.connectionPool().evictAll();}
}
