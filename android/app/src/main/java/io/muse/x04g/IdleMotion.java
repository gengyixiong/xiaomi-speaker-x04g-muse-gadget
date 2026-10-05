package io.muse.x04g;

import java.util.Random;

// Only selects existing Muse poses. Connection and voice states stay in MuseService.
final class IdleMotion {
    final Random random=new Random();
    long nextAt,started,until;
    int action=-1,last=-1,mode=1;
    float seconds,happy,level;

    void update(boolean idle,long now) {
        mode=1;seconds=happy=level=0;
        if(!idle){action=-1;nextAt=0;return;}
        if(nextAt==0)nextAt=now+3000+random.nextInt(4000);
        if(action>=0&&now>=until)action=-1;
        if(action<0&&now>=nextAt){
            int pick=random.nextInt(6);
            if(pick==last)pick=(pick+1+random.nextInt(5))%6;
            action=last=pick;started=now;
            until=now+(pick==0?1600:2200+random.nextInt(1000));
            nextAt=until+8000+random.nextInt(12000);
        }
        if(action<0)return;
        seconds=(now-started)/1000f;
        switch(action){
            case 0: happy=Math.min(1,(until-now)/400f);break;
            // OFF already has a wave. Holding mode_t at zero keeps eyes open
            // and prevents the shutdown fade; global t still moves the arm.
            case 1: mode=6;seconds=0;break;
            case 2: mode=3;break;
            case 3: mode=4;level=0.3f+0.15f*(float)Math.sin(seconds*6);break;
            case 4: mode=2;level=0.15f+0.1f*(float)Math.sin(seconds*3);break;
            case 5: mode=0;break;
        }
    }

    // Runnable with javac/java -ea; no Android, test framework or real delays.
    public static void main(String[] args){
        IdleMotion m=new IdleMotion();m.random.setSeed(5);m.update(true,1);
        boolean[] seen=new boolean[6];
        for(int i=0;i<24;i++){
            m.update(true,m.nextAt);seen[m.action]=true;
            assert m.mode!=5 && m.until>m.started;
            if(m.mode==6)assert m.seconds==0;
            m.update(true,m.until+1);
            assert m.action==-1 && m.mode==1 && m.happy==0 && m.nextAt>m.until;
        }
        for(boolean present:seen)assert present;
        m.update(true,m.nextAt);m.update(false,m.started+200);
        assert m.action==-1 && m.mode==1 && m.happy==0 && m.nextAt==0;
        System.out.println("Idle motion check passed: six poses, rest gaps, PTT priority, no shutdown fade");
    }
}
