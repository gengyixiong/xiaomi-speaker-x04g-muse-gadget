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

    static final int[] SIRI_COLORS={0xFF65DFFF,0xFF647BFF,0xFFB278FF,0xFFFF82CF,0xFFFFA18D,0xFFA981FF,0xFF65DFFF};
    static final float[] SIRI_POS={0f,0.18f,0.36f,0.53f,0.68f,0.84f,1f};
    static final float DEFAULT_CORNER_RADIUS=64;
    final Paint glowPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    final Matrix glowMatrix=new Matrix();
    Shader glowColors,glowShader;
    float glowAlpha,glowLevel,glowRadius=-1;
    long lastDrawTime;

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
    }
    // Distance to the rounded screen edge gives both a soft rim and an inward haze.
    static float glowOpacity(float x,float y,float width,float height,float radius) {
        float scale=Math.min(width/800f,height/480f),inset=2*scale;
        float r=Math.min(radius*scale,Math.min(width,height)/2-inset);
        float qx=Math.abs(x-width/2)-(width/2-inset-r),qy=Math.abs(y-height/2)-(height/2-inset-r);
        float distance=(float)Math.hypot(Math.max(qx,0),Math.max(qy,0))+Math.min(Math.max(qx,qy),0)-r;
        float coverage=Math.max(0,Math.min(1,0.5f-distance/(2*scale)));
        float depth=Math.max(0,-distance)/scale;
        return coverage*(float)(0.55*Math.exp(-depth*depth/72)+0.45*Math.exp(-depth*depth/3528));
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);glowShader=null;}
    void drawGlow(Canvas canvas,long now,float radius) {
        int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        if(glowShader==null||glowRadius!=radius){
            // ponytail: cache at half resolution; regenerate only on resize or corner calibration.
            int mw=(w+1)/2,mh=(h+1)/2;int[] pixels=new int[mw*mh];
            for(int y=0;y<mh;y++)for(int x=0;x<mw;x++)pixels[y*mw+x]=Color.argb(Math.round(255*glowOpacity((x+0.5f)*w/mw,(y+0.5f)*h/mh,w,h,radius)),255,255,255);
            Bitmap mask=Bitmap.createBitmap(pixels,mw,mh,Bitmap.Config.ARGB_8888);
            Shader fade=new BitmapShader(mask,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            Matrix size=new Matrix();size.setScale((float)w/mw,(float)h/mh);fade.setLocalMatrix(size);
            glowColors=new SweepGradient(w/2f,h/2f,SIRI_COLORS,SIRI_POS);
            glowShader=new ComposeShader(glowColors,fade,PorterDuff.Mode.DST_IN);glowRadius=radius;
            glowPaint.setShader(glowShader);
        }
        float t=(now-started)/1000f,cx=w/2f,cy=h/2f;
        glowMatrix.setScale(1+0.08f*(float)Math.sin(t*0.43f),1+0.1f*(float)Math.cos(t*0.37f),cx,cy);
        glowMatrix.postRotate((t*18+10*(float)Math.sin(t*0.31f))%360,cx,cy);
        glowMatrix.postTranslate(w*0.04f*(float)Math.sin(t*0.27f),h*0.06f*(float)Math.cos(t*0.23f));
        glowColors.setLocalMatrix(glowMatrix);
        float breath=0.82f+0.07f*(float)Math.sin(t*1.4f)+0.11f*glowLevel;
        glowPaint.setAlpha(Math.round(255*glowAlpha*breath));
        canvas.drawRect(0,0,w,h,glowPaint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);MuseService s=MuseService.instance;long now=SystemClock.elapsedRealtime();
        String state=s==null?"BOOT":s.state;
        int mode=state.equals("IDLE")?1:state.equals("LISTENING")?2:state.equals("THINKING")?3:state.equals("SPEAKING")?4:state.equals("ERROR")?5:0;
        motion.update(state.equals("IDLE"),now);
        float dt=lastDrawTime==0?0.04f:Math.min(0.1f,(now-lastDrawTime)/1000f);lastDrawTime=now;
        boolean edgeEnabled=s==null||s.store==null||s.store.prefs.getBoolean("edge_glow",true);
        float targetAlpha=(edgeEnabled&&s!=null)?(state.equals("LISTENING")?1f:state.equals("THINKING")?0.65f:0f):0f;
        glowAlpha+=(targetAlpha-glowAlpha)*(1-(float)Math.exp(-dt*(targetAlpha>glowAlpha?7f:2.8f)));
        float targetLevel=state.equals("LISTENING")&&s!=null?Math.max(0,Math.min(1,s.level)):0;
        glowLevel+=(targetLevel-glowLevel)*(1-(float)Math.exp(-dt*(targetLevel>glowLevel?12f:3f)));
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
        if(glowAlpha>0.01f)drawGlow(canvas,now,s==null||s.store==null?DEFAULT_CORNER_RADIUS:s.store.prefs.getFloat("edge_corner_radius",DEFAULT_CORNER_RADIUS));
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
