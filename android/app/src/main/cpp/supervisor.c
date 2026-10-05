#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define APP_FILES "/data/user/0/io.muse.x04g/files/"
#define MAINT APP_FILES "maintenance"
static pid_t child;
static long long child_started;
static long long now_ms(void){struct timespec t;clock_gettime(CLOCK_BOOTTIME,&t);return t.tv_sec*1000LL+t.tv_nsec/1000000;}
static int exists(const char* p){return access(p,F_OK)==0;}
static long long stamp(const char* p){long long n=0;FILE* f=fopen(p,"r");if(f){if(fscanf(f,"%lld",&n)!=1)n=0;fclose(f);}return n;}
static void mark(void){int fd=open(MAINT,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0644);if(fd>=0){write(fd,"1",1);close(fd);}}
static void launch(const char* cmd){
    if(child)return;
    child=fork();child_started=now_ms();
    if(child==0){setsid();int fd=open("/dev/null",O_RDWR);dup2(fd,0);dup2(fd,1);dup2(fd,2);if(fd>2)close(fd);execl("/system/bin/sh","sh","-c",cmd,(char*)0);_exit(127);}
    if(child<0)child=0;
}
static void stop_child(void){if(child){kill(-child,SIGTERM);kill(child,SIGTERM);waitpid(child,NULL,WNOHANG);child=0;}}
static void escape(void){mark();stop_child();launch("am force-stop io.muse.x04g\nam start -a android.intent.action.MAIN -c android.intent.category.HOME");}
static int keys[2]={-1,-1};
static void inputs(void){
    DIR* dir=opendir("/dev/input");if(!dir)return;struct dirent* entry;
    while((entry=readdir(dir))){
        if(strncmp(entry->d_name,"event",5))continue;
        char path[512],name[128]={0};snprintf(path,sizeof(path),"/dev/input/%s",entry->d_name);
        int fd=open(path,O_RDONLY|O_NONBLOCK|O_CLOEXEC);if(fd<0)continue;
        if(ioctl(fd,EVIOCGNAME(sizeof(name)),name)>=0){int index=!strcmp(name,"mtk-kpd")?0:!strcmp(name,"mtk-pmic-keys")?1:-1;if(index>=0&&keys[index]<0){keys[index]=fd;continue;}}
        close(fd);
    }closedir(dir);
}
static int held(int key){
    // Query the kernel's current key bitmap: no grab and no event parser.
    unsigned char bits[(KEY_MAX+8)/8];
    for(int i=0;i<2;i++)if(keys[i]>=0){memset(bits,0,sizeof(bits));if(ioctl(keys[i],EVIOCGKEY(sizeof(bits)),bits)>=0&&(bits[key/8]&(1<<(key%8))))return 1;}
    return 0;
}
int main(int argc,char** argv){
    if(argc!=2||getuid()!=0)return 2;
    char lock[768],persistent[768];snprintf(persistent,sizeof(persistent),"%s/maintenance",argv[1]);snprintf(lock,sizeof(lock),"%s/supervisor.lock",argv[1]);int guard=open(lock,O_CREAT|O_RDWR|O_CLOEXEC,0600);
    if(guard<0||flock(guard,LOCK_EX|LOCK_NB)!=0)return 3;
    inputs();if(keys[0]<0||keys[1]<0){fprintf(stderr,"Missing X04G input devices\n");return 4;}
    long long since=0,last_recovery=0;int escaped=0;
    while(1){
        long long now=now_ms();
        if(child){int result=waitpid(child,NULL,WNOHANG);if(result==child||result<0)child=0;else if(now-child_started>10000)stop_child();}
        if(held(KEY_VOLUMEUP)&&held(KEY_VOLUMEDOWN)){if(!since)since=now;if(!escaped&&now-since>=5000){escaped=1;escape();}}else{since=0;escaped=0;}
        FILE* req=fopen(APP_FILES "maintenance-request","r");
        if(req){char command[32]={0};fgets(command,sizeof(command),req);fclose(req);unlink(APP_FILES "maintenance-request");if(!strcmp(command,"reboot")){mark();stop_child();launch("/system/bin/reboot");}}
        if(!exists(MAINT)&&!exists(persistent)&&!child&&now-last_recovery>=15000){
            long long service=stamp(APP_FILES "service-heartbeat"),ui=stamp(APP_FILES "ui-heartbeat");
            if(service<=0||service>now||now-service>45000){
                last_recovery=now;
                launch("am force-stop io.muse.x04g\nam start-foreground-service -n io.muse.x04g/.MuseService\nam start -n io.muse.x04g/.MainActivity");
            }else if(ui<=0||ui>now||now-ui>15000){last_recovery=now;launch("am start -n io.muse.x04g/.MainActivity");}
        }
        while(waitpid(-1,NULL,WNOHANG)>0){}
        struct timespec pause={0,100000000};nanosleep(&pause,NULL);
    }
}
