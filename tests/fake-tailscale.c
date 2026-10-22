// Test fixture, not shipped in the APK. Models separate inbox read and delete operations.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <limits.h>
int main(int argc, char **argv) {
    const char *base = getenv("TC_FIXTURE");
    if (!base || argc < 3) return 64;
    char calls[PATH_MAX], inbox[PATH_MAX], modefile[PATH_MAX];
    snprintf(calls,sizeof calls,"%s/calls",base);
    snprintf(inbox,sizeof inbox,"%s/inbox",base);
    snprintf(modefile,sizeof modefile,"%s/mode",base);
    char mode[80]="normal";
    FILE *m=fopen(modefile,"r"); if(m){if(fscanf(m,"%79s",mode)!=1)strcpy(mode,"normal"); fclose(m);}
    if(strcmp(argv[1],"file") || strcmp(argv[2],"get")) { sleep(60); return 0; }
    int loop=0;
    for(int i=3;i<argc;i++) if(!strcmp(argv[i],"--loop")) loop=1;
    if(!strcmp(mode,"close-lock")) close(8);
    do {
        FILE *log=fopen(calls,"a"); if(log){fprintf(log,"%d\n",getpid());fclose(log);}
        if(!strcmp(mode,"slow") || !strcmp(mode,"close-lock")){sleep(60); return 0;}
        if(!strcmp(mode,"fail")){fprintf(stderr,"fixture CLI failure\n");return 31;}
        FILE *f=fopen(inbox,"rb");
        if(f){
            char data[4096]; size_t n=fread(data,1,sizeof data,f);fclose(f);
            if(!strcmp(mode,"barrier")){
                char opened[PATH_MAX], go[PATH_MAX];
                snprintf(opened,sizeof opened,"%s/opened-%d",base,getpid());
                snprintf(go,sizeof go,"%s/go",base);
                int mark=open(opened,O_WRONLY|O_CREAT,0600);if(mark>=0)close(mark);
                for(int i=0;i<500 && access(go,F_OK);i++)usleep(10000);
            }
            usleep(50000); // Deliberately allow two old receivers to open the same item.
            char output[PATH_MAX]; int fd=-1;
            for(int i=0;i<100;i++){
                snprintf(output,sizeof output,"%s/receipt-%d",argv[argc-1],i);
                fd=open(output,O_WRONLY|O_CREAT|O_EXCL,0600);
                if(fd>=0) break;
            }
            if(fd<0) return 73;
            if(write(fd,data,n)!=(ssize_t)n){close(fd);return 73;}close(fd);
            fprintf(stdout,"wrote fixture as %s (%zu bytes)\n",output,n);fflush(stdout);
            if(!strcmp(mode,"delete-fail")){fprintf(stderr,"deleting fixture from inbox: simulated failure\n");return 32;}
            if(unlink(inbox) && errno!=ENOENT) return 33;
        }
        if(loop) sleep(1);
    } while(loop);
    return 0;
}
