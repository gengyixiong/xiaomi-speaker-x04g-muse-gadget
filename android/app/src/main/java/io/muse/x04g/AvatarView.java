package io.muse.x04g;

import android.content.Context;
import android.graphics.*;
import android.media.AudioManager;
import android.os.SystemClock;
import android.text.*;
import android.view.*;

final class AvatarView extends View {
    final MainActivity activity;
    final Bitmap bitmap=Bitmap.createBitmap(384,384,Bitmap.Config.RGB_565);
    final Paint paint=new Paint(),scrimPaint=new Paint();final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final IdleMotion motion=new IdleMotion();
    static final long started=SystemClock.elapsedRealtime();
    long cornerStarted;
    String shown="";StaticLayout caption;
    final Runnable settings;
    final Runnable hold;

    static final int[] SIRI_COLORS={0xFF00F0FF,0xFF007AFF,0xFF8A2BE2,0xFFFF2D55,0xFFFF7A00,0xFF00FF9D,0xFF00F0FF};
    static final float[] SIRI_POS={0f,0.16f,0.35f,0.55f,0.74f,0.90f,1f};
    final Paint glowPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    final Matrix glowMatrix=new Matrix();
    final RectF glowRect=new RectF();
    Shader glowShader;
    float glowAlpha,lastDrawTime;

    float downX,downY,lastY,volAccumulator;
    long downTime;
    boolean dragging,isLeft;
    String feedback;
    long feedbackUntil;

    AvatarView(MainActivity activity,Runnable settings){
        super(activity);this.activity=activity;this.settings=settings;
        hold=()->{if(cornerStarted!=0){cornerStarted=0;settings.run();}};
        setBackgroundColor(Color.BLACK);text.setColor(Color.WHITE);text.setTextSize(28);
        scrimPaint.setColor(Color.argb(160,0,0,0));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);MuseService s=MuseService.instance;long now=SystemClock.elapsedRealtime();
        String state=s==null?"BOOT":s.state;
        int mode=state.equals("IDLE")?1:state.equals("LISTENING")?2:state.equals("THINKING")?3:state.equals("SPEAKING")?4:state.equals("ERROR")?5:0;
        motion.update(state.equals("IDLE"),now);
        float dt=lastDrawTime==0?0.04f:Math.min(0.1f,(now-lastDrawTime)/1000f);lastDrawTime=now;
        boolean edgeEnabled=s==null||s.store==null||s.store.prefs.getBoolean("edge_glow",true);
        float targetAlpha=(edgeEnabled&&s!=null)?(state.equals("LISTENING")?1f:state.equals("THINKING")?0.65f:0f):0f;
        if(glowAlpha<targetAlpha)glowAlpha=Math.min(targetAlpha,glowAlpha+dt*6f);
        else if(glowAlpha>targetAlpha)glowAlpha=Math.max(targetAlpha,glowAlpha-dt*3.5f);
        float happy=0,modeTime=s==null?0:(now-s.modeStarted)/1000f;
        if(s!=null&&s.happyUntil>now){
            long left=s.happyUntil-now;
            happy=left>400?1.0f:left/400f;
        }
        float level=s==null?0:mode==4?(s.level>0.05f?Math.min(1.0f,s.level+0.05f*(float)Math.sin(now/60f)):0f):s.level;
        if(happy>0){
            mode=1;level=0;
        }else if(state.equals("IDLE")){
            mode=motion.mode;modeTime=motion.seconds;level=motion.level;happy=motion.happy;
        }
        Native.avatar(bitmap,mode,(now-started)/1000f,modeTime,level,happy);
        boolean upper=s==null||s.store==null||s.store.prefs.getBoolean("upper_body",true);
        if(upper){
            Rect src=new Rect(0,0,384,256);
            float dstH=getHeight();
            float dstW=dstH*1.5f;
            float dstX=(getWidth()-dstW)/2f,dstY=0;
            canvas.drawBitmap(bitmap,src,new RectF(dstX,dstY,dstX+dstW,dstY+dstH),paint);
        }else{
            int side=Math.min(384,Math.min(getWidth(),getHeight()-76));float x=(getWidth()-side)/2f,y=Math.max(0,(getHeight()-side-76)/2f);
            canvas.drawBitmap(bitmap,null,new RectF(x,y,x+side,y+side),paint);
        }
        if(glowAlpha>0.01f){
            if(glowShader==null&&getWidth()>0)glowShader=new SweepGradient(getWidth()/2f,getHeight()/2f,SIRI_COLORS,SIRI_POS);
            if(glowShader!=null){
                float angle=(now*0.16f)%360f;
                glowMatrix.setRotate(angle,getWidth()/2f,getHeight()/2f);
                glowShader.setLocalMatrix(glowMatrix);
                glowPaint.setShader(glowShader);
                float audioLevel=(state.equals("LISTENING")&&s!=null)?s.level:0f;

                float auraW=28f+22f*audioLevel;
                glowPaint.setStrokeWidth(auraW);
                glowPaint.setAlpha((int)(110*glowAlpha*(0.8f+0.35f*audioLevel)));
                float o1=auraW/2f;
                glowRect.set(o1,o1,getWidth()-o1,getHeight()-o1);
                canvas.drawRoundRect(glowRect,28f,28f,glowPaint);

                float coreW=8f+6f*audioLevel;
                glowPaint.setStrokeWidth(coreW);
                glowPaint.setAlpha((int)(250*glowAlpha));
                float o2=coreW/2f;
                glowRect.set(o2,o2,getWidth()-o2,getHeight()-o2);
                canvas.drawRoundRect(glowRect,24f,24f,glowPaint);
            }
        }
        String value=s==null||state.equals("LISTENING")?"":s.caption;
        if(!shown.equals(value)){shown=value;caption=value.isEmpty()?null:StaticLayout.Builder.obtain(value,0,value.length(),text,Math.max(1,getWidth()-40)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();}
        if(caption!=null){
            int line=caption.getLineForOffset(Math.min(shown.length(),s==null?0:s.captionOffset));
            int top=caption.getLineTop(line/2*2);
            canvas.save();
            canvas.clipRect(0,getHeight()-96,getWidth(),getHeight());
            if(upper)canvas.drawRect(0,getHeight()-96,getWidth(),getHeight(),scrimPaint);
            canvas.translate(20,getHeight()-Math.min(90,caption.getHeight())-6-top);
            caption.draw(canvas);
            canvas.restore();
        }
        if(state.equals("BOOT")||state.equals("OFFLINE")||state.equals("ERROR")){String badge=state.equals("OFFLINE")?"OFFLINE · reconnecting":state;text.setTextSize(18);canvas.drawText(badge,getWidth()/2f-text.measureText(badge)/2f,25,text);text.setTextSize(28);}
        if(feedbackUntil>now&&feedback!=null){
            text.setTextSize(20);int fy=(state.equals("BOOT")||state.equals("OFFLINE")||state.equals("ERROR"))?52:32;
            float tw=text.measureText(feedback);float tx=getWidth()/2f-tw/2f;
            scrimPaint.setColor(Color.argb(180,0,0,0));
            canvas.drawRoundRect(tx-16,fy-22,tx+tw+16,fy+8,12,12,scrimPaint);
            scrimPaint.setColor(Color.argb(160,0,0,0));
            canvas.drawText(feedback,tx,fy,text);text.setTextSize(28);
        }
        postInvalidateDelayed(40);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        long now=SystemClock.elapsedRealtime();
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            downX=e.getX();downY=lastY=e.getY();downTime=now;dragging=false;volAccumulator=0;
            isLeft=downX<getWidth()/2f;
            if(downX>getWidth()-96&&downY<96){cornerStarted=now;postDelayed(hold,3000);}
            return true;
        }
        if(action==MotionEvent.ACTION_MOVE){
            float dy=e.getY()-lastY;
            float totalDy=e.getY()-downY;
            float totalDx=e.getX()-downX;
            if(!dragging&&(Math.abs(totalDy)>20||Math.abs(totalDx)>20)){
                dragging=true;cornerStarted=0;removeCallbacks(hold);
            }
            if(dragging&&Math.abs(dy)>=1){
                lastY=e.getY();
                if(isLeft){
                    float delta=-dy/(float)getHeight();
                    activity.adjustBrightness(delta);
                    android.content.SharedPreferences p=activity.getSharedPreferences("muse",Context.MODE_PRIVATE);
                    feedback="☀ 亮度 "+Math.round(p.getFloat("brightness",0.5f)*100)+"%";
                    feedbackUntil=now+1500;
                }else{
                    volAccumulator+=-dy;
                    int stepDist=Math.max(16,getHeight()/18);
                    if(Math.abs(volAccumulator)>=stepDist){
                        int step=(int)(volAccumulator/stepDist);
                        volAccumulator-=step*stepDist;
                        int cur=activity.adjustVolume(step);
                        AudioManager am=activity.getSystemService(AudioManager.class);
                        feedback="♪ 音量 "+cur+"/"+am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        feedbackUntil=now+1500;
                    }
                }
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            cornerStarted=0;removeCallbacks(hold);
            if(!dragging&&now-downTime<500){
                if(downX<=getWidth()-96||downY>=96){
                    MuseService s=MuseService.instance;
                    if(s!=null){
                        if(s.state.equals("SPEAKING")||(s.speech!=null&&s.speech.busy())){
                            s.speech.stop();s.setState("IDLE");s.makeHappy();s.hideCaptionLater();
                        }else{
                            s.makeHappy();
                        }
                    }
                }
            }
            return true;
        }
        return true;
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(hold);super.onDetachedFromWindow();}
}
