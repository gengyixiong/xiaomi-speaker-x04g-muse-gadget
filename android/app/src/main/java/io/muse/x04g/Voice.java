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
    void start(long generation) throws Exception {
        stop();
        int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IllegalStateException("16k mono recording unavailable");
        AudioRecord r=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,6144));
        if(r.getState()!=AudioRecord.STATE_INITIALIZED){r.release();throw new IllegalStateException("microphone init failed");}
        for(AudioDeviceInfo d:service.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){r.setPreferredDevice(d);break;}
        record=r;r.startRecording();
        service.link.beginVoice(generation);
        new Thread(()->capture(r,generation),"MuseMicrophone").start();
    }
    void capture(AudioRecord r,long generation) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] stage=new byte[3072];System.arraycopy(wav(),0,stage,0,44);int staged=44,total=0,measured=0,clipped=0;float peak=0,rawPeak=0;double squares=0;
        try {
            while(record==r&&generation==service.turnGeneration&&total<480000) {
                int n=r.read(stage,staged,Math.min(stage.length-staged,480000-total),AudioRecord.READ_BLOCKING);
                if(n<0){if(record!=r)break;throw new IllegalStateException("microphone read "+n);}
                if(n==0)continue;
                float gain=service.store.prefs.getFloat("mic_gain",5);
                double energy=0,rawEnergy=0;
                for(int i=staged;i+1<staged+n;i+=2){
                    int sample=(short)((stage[i]&255)|(stage[i+1]<<8));rawEnergy+=(double)sample*sample;
                    int amplified=Math.round(sample*gain);if(amplified>32767||amplified<-32768)clipped++;
                    sample=Math.max(-32768,Math.min(32767,amplified));
                    stage[i]=(byte)sample;stage[i+1]=(byte)(sample>>8);energy+=(double)sample*sample;
                }
                rawPeak=Math.max(rawPeak,(float)Math.sqrt(rawEnergy/Math.max(1,n/2))/6000);
                if(total>=6400){squares+=rawEnergy;measured+=n/2;}
                service.level=(float)Math.min(1,Math.sqrt(energy/Math.max(1,n/2))/6000);
                peak=Math.max(peak,service.level);
                if(total==0){AudioDeviceInfo routed=r.getRoutedDevice();Log.i("MuseX04G","capture route type="+(routed==null?-1:routed.getType())+" muted="+service.getSystemService(AudioManager.class).isMicrophoneMute());}
                staged+=n;total+=n;
                if(staged==stage.length){if(!service.link.voiceChunk(generation,Pairing.utf(Base64.getEncoder().encodeToString(stage)),false))throw new IllegalStateException("audio network queue full");staged=0;}
            }
            if(generation==service.turnGeneration) {
                if(total<9600){service.link.abortVoice(generation);service.main.post(()->{if(generation==service.turnGeneration){service.setState("IDLE");service.status="Press longer than 0.3s";}});}
                else {
                    byte[] tail=Pairing.join(Pairing.utf(Base64.getEncoder().encodeToString(Arrays.copyOf(stage,staged))),Pairing.utf("\"}]}"));
                    if(!service.link.voiceChunk(generation,tail,true))throw new IllegalStateException("audio network queue full");
                    service.main.post(()->{if(generation==service.turnGeneration)service.setState("THINKING");});
                    Log.i("MuseX04G","voice submitted PCM bytes="+total+" rawRms="+Math.round(Math.sqrt(squares/Math.max(1,measured)))+" rawPeak="+rawPeak+" peak="+peak+" gain="+service.store.prefs.getFloat("mic_gain",5)+" clipped="+clipped+" muted="+service.getSystemService(AudioManager.class).isMicrophoneMute());
                }
            }
        } catch(Exception e){if(generation==service.turnGeneration){service.link.abortVoice(generation);service.main.post(()->{if(generation==service.turnGeneration){service.status="Audio error: "+e.getClass().getSimpleName();service.setState("ERROR");}});}}
        finally {if(record==r)record=null;try{r.stop();}catch(IllegalStateException ignored){}r.release();Arrays.fill(stage,(byte)0);service.level=0;}
    }
    void stop(){AudioRecord r=record;record=null;if(r!=null)try{r.stop();}catch(IllegalStateException ignored){}}
}
