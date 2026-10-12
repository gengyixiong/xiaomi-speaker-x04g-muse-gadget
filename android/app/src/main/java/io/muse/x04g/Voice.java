package io.muse.x04g;

import android.media.*;
import android.os.Process;
import android.util.Log;
import java.nio.*;
import java.util.*;

final class Voice {
    final MuseService service;
    volatile AudioRecord record;
    Voice(MuseService service){this.service=service;}
    static byte[] wav() {
        ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(Pairing.utf("RIFF")).putInt(-1).put(Pairing.utf("WAVEfmt ")).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put(Pairing.utf("data")).putInt(-1);
        return b.array();
    }
    static int softLimit(int x) {
        if(x<=24000&&x>=-24000)return x;
        boolean neg=x<0;long excess=neg?-x-24000L:x-24000L;
        int smooth=(int)(24000L+(8767L*excess)/(excess+8767L));
        return neg?-smooth:smooth;
    }
    // Route settle: the codec needs time to power the mic path before the first
    // samples are meaningful. Without this the leading chunks carry the DC step
    // of a not-yet-running ADC - the click heard at the start of playback.
    // Discard this much audio after start: codec power-up, not speech. The HAL
    // emits a full-scale step when the mic path turns on (measured rms 13353,
    // peak 32767 in the first 100 ms), which is the click heard at note start.
    static final int SETTLE_MS=250;
    // A route that never exceeds this raw peak is returning silence, not sound:
    // MTK reports success with Frames read: 0 instead of failing.
    static final int DEAD_PEAK=2;
    void start(long generation) throws Exception {
        stop();
        int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IllegalStateException("16k mono recording unavailable");
        AudioRecord r=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,6144));
        if(r.getState()!=AudioRecord.STATE_INITIALIZED){r.release();throw new IllegalStateException("microphone init failed");}
        // Do not pin TYPE_BUILTIN_MIC. The MTK HAL advertises that device but its
        // input channel returns 0 frames (Frames read: 0, Signal power: none).
        // AudioFlinger routes AUDIO_SOURCE_MIC correctly by itself (42560+ frames).
        record=r;r.startRecording();
        service.link.beginVoice(generation);
        new Thread(()->capture(r,generation),"MuseMicrophone").start();
    }
    void capture(AudioRecord r,long generation) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] stage=new byte[3072];System.arraycopy(wav(),0,stage,0,44);int staged=44,total=0,dropped=0,measured=0,clipped=0;float peak=0,rawPeak=0;double squares=0;
        long settleUntil=android.os.SystemClock.elapsedRealtime()+SETTLE_MS;int maxRawPeak=0;
        byte[] scratch=new byte[640];
        try {
            // Drain the power-up window into scratch so no transient reaches the note.
            while(record==r&&generation==service.turnGeneration&&android.os.SystemClock.elapsedRealtime()<settleUntil){
                int n=r.read(scratch,0,scratch.length,AudioRecord.READ_BLOCKING);
                if(n<0){if(record!=r)break;throw new IllegalStateException("microphone read "+n);}
                dropped+=Math.max(0,n);
            }
            while(record==r&&generation==service.turnGeneration&&total<480000) {
                int n=r.read(stage,staged,Math.min(stage.length-staged,480000-total),AudioRecord.READ_BLOCKING);
                if(n<0){if(record!=r)break;throw new IllegalStateException("microphone read "+n);}
                if(n==0)continue;
                float gain=service.store.prefs.getFloat("mic_gain",5);
                double energy=0,rawEnergy=0;int blockPeak=0;
                for(int i=staged;i+1<staged+n;i+=2){
                    int sample=(short)((stage[i]&255)|(stage[i+1]<<8));rawEnergy+=(double)sample*sample;
                    blockPeak=Math.max(blockPeak,Math.abs(sample));
                    int amplified=Math.round(sample*gain);if(amplified>32767||amplified<-32768)clipped++;
                    sample=softLimit(amplified);
                    stage[i]=(byte)sample;stage[i+1]=(byte)(sample>>8);energy+=(double)sample*sample;
                }
                maxRawPeak=Math.max(maxRawPeak,blockPeak);
                rawPeak=Math.max(rawPeak,(float)Math.sqrt(rawEnergy/Math.max(1,n/2))/6000);
                if(total>=6400){squares+=rawEnergy;measured+=n/2;}
                service.level=(float)Math.min(1,Math.sqrt(energy/Math.max(1,n/2))/6000);
                peak=Math.max(peak,service.level);
                if(total==0){AudioDeviceInfo routed=r.getRoutedDevice();Log.i("MuseX04G","capture route type="+(routed==null?-1:routed.getType())+" muted="+service.getSystemService(AudioManager.class).isMicrophoneMute());}
                staged+=n;total+=n;
                if(staged==stage.length){if(!service.link.voiceChunk(generation,Pairing.utf(Base64.getEncoder().encodeToString(stage)),false))throw new IllegalStateException("audio network queue full");staged=0;}
            }
            if(maxRawPeak<=DEAD_PEAK&&total>=9600)Log.w("MuseX04G","capture produced silence: maxRawPeak="+maxRawPeak+" - audio route is dead");
            if(generation==service.turnGeneration) {
                if(total<9600){service.link.abortVoice(generation);service.main.post(()->{if(generation==service.turnGeneration){service.setState("IDLE");service.status="Press longer than 0.3s";}});}
                else {
                    fadeOut(stage,staged);
                    byte[] tail=Pairing.join(Pairing.utf(Base64.getEncoder().encodeToString(Arrays.copyOf(stage,staged))),Pairing.utf("\"}]}"));
                    if(!service.link.voiceChunk(generation,tail,true))throw new IllegalStateException("audio network queue full");
                    service.main.post(()->{if(generation==service.turnGeneration)service.setState("THINKING");});
                    Log.i("MuseX04G","voice submitted PCM bytes="+total+" rawRms="+Math.round(Math.sqrt(squares/Math.max(1,measured)))+" rawPeak="+rawPeak+" maxRawPeak="+maxRawPeak+" dropped="+dropped+" peak="+peak+" gain="+service.store.prefs.getFloat("mic_gain",5)+" clipped="+clipped+" muted="+service.getSystemService(AudioManager.class).isMicrophoneMute());
                }
            }
        } catch(Exception e){if(generation==service.turnGeneration){service.link.abortVoice(generation);service.main.post(()->{if(generation==service.turnGeneration){service.status="Audio error: "+e.getClass().getSimpleName();service.setState("ERROR");}});}}
        finally {if(record==r)record=null;try{r.stop();}catch(IllegalStateException ignored){}r.release();Arrays.fill(stage,(byte)0);Arrays.fill(scratch,(byte)0);service.level=0;}
    }
    // Linear fade over the trailing samples removes the click Muse plays back at the
    // end of a note: a hard stop truncates the waveform mid-cycle.
    static void fadeOut(byte[] buf,int len){int samples=(len-44)/2;if(samples<=0)return;int fade=Math.min(samples,800);for(int i=0;i<fade;i++){int at=44+(samples-fade+i)*2;int s=(short)((buf[at]&255)|(buf[at+1]<<8));s=s*(fade-i)/fade;buf[at]=(byte)s;buf[at+1]=(byte)(s>>8);}}
    void stop(){AudioRecord r=record;record=null;if(r!=null)try{r.stop();}catch(IllegalStateException ignored){}}
}
