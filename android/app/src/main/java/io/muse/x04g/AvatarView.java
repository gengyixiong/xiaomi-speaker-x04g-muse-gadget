package io.muse.x04g;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.text.*;
import android.view.*;

final class AvatarView extends View {
    final Bitmap bitmap=Bitmap.createBitmap(384,384,Bitmap.Config.RGB_565);
    final Paint paint=new Paint();final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final IdleMotion motion=new IdleMotion();
    static final long started=SystemClock.elapsedRealtime();
    long cornerStarted;
    String shown="";StaticLayout caption;
    final Runnable settings;
    final Runnable hold;
    AvatarView(Context context,Runnable settings){super(context);this.settings=settings;hold=()->{if(cornerStarted!=0){cornerStarted=0;settings.run();}};setBackgroundColor(Color.BLACK);text.setColor(Color.WHITE);text.setTextSize(28);}
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);MuseService s=MuseService.instance;long now=SystemClock.elapsedRealtime();
        String state=s==null?"BOOT":s.state;
        int mode=state.equals("IDLE")?1:state.equals("LISTENING")?2:state.equals("THINKING")?3:state.equals("SPEAKING")?4:state.equals("ERROR")?5:0;
        motion.update(state.equals("IDLE"),now);
        float happy=0,modeTime=s==null?0:(now-s.modeStarted)/1000f;
        // TTS owns playback; its speaking motion uses the renderer's
        // time animation. Only LISTENING has a measured live audio level.
        float level=s==null?0:mode==4?0.35f+0.25f*(float)Math.sin(now/90f):s.level;
        if(state.equals("IDLE")){mode=motion.mode;modeTime=motion.seconds;level=motion.level;happy=motion.happy;}
        Native.avatar(bitmap,mode,(now-started)/1000f,modeTime,level,happy);
        int side=Math.min(384,Math.min(getWidth(),getHeight()-76));float x=(getWidth()-side)/2f,y=Math.max(0,(getHeight()-side-76)/2f);
        canvas.drawBitmap(bitmap,null,new RectF(x,y,x+side,y+side),paint);
        String value=s==null||state.equals("LISTENING")?"":s.caption;
        if(!shown.equals(value)){shown=value;caption=value.isEmpty()?null:StaticLayout.Builder.obtain(value,0,value.length(),text,Math.max(1,getWidth()-40)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();}
        if(caption!=null){int line=caption.getLineForOffset(Math.min(shown.length(),s==null?0:s.captionOffset));int top=caption.getLineTop(line/2*2);canvas.save();canvas.clipRect(0,getHeight()-96,getWidth(),getHeight());canvas.translate(20,getHeight()-Math.min(90,caption.getHeight())-6-top);caption.draw(canvas);canvas.restore();}
        if(state.equals("BOOT")||state.equals("OFFLINE")||state.equals("ERROR")){String badge=state.equals("OFFLINE")?"OFFLINE · reconnecting":state;text.setTextSize(18);canvas.drawText(badge,getWidth()/2f-text.measureText(badge)/2f,25,text);text.setTextSize(28);}
        postInvalidateDelayed(40);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN&&e.getX()>getWidth()-96&&e.getY()<96){cornerStarted=SystemClock.elapsedRealtime();postDelayed(hold,3000);return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL||e.getX()<=getWidth()-96||e.getY()>=96){cornerStarted=0;removeCallbacks(hold);}
        return true;
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(hold);super.onDetachedFromWindow();}
}
