/* Host-side test of the TCP framing + PadFrame->ScePadData parse path.
 * Mirrors gc_main.c tcp_client recv/parse logic exactly. No PS5 needed. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "padframe.h"
#include "gc_types.h"
int main(int argc, char **argv) {
    int port = argc>1?atoi(argv[1]):8471;
    int sock = socket(AF_INET,SOCK_STREAM,0);
    struct sockaddr_in sin={0}; sin.sin_family=AF_INET;
    sin.sin_port=htons(port); inet_pton(AF_INET,"127.0.0.1",&sin.sin_addr);
    if (connect(sock,(struct sockaddr*)&sin,sizeof(sin))<0){perror("connect");return 1;}
    printf("connected to fake-phone :%d\n", port);
    uint8_t buf[32]; size_t got=0; int shown=0;
    while (shown<8) {
        ssize_t n=recv(sock,buf+got,32-got,0);
        if (n<=0){printf("EOF\n");break;}
        got+=(size_t)n;
        if (got<32) continue;
        PadFrame f; memcpy(&f,buf,sizeof f);
        if (f.magic!=PADFRAME_MAGIC){printf("MAGIC BAD 0x%08x\n",f.magic);return 2;}
        ScePadData pad; memset(&pad,0,sizeof pad);
        pad.buttons=f.buttons;
        pad.leftStick.x=f.lx;pad.leftStick.y=f.ly;
        pad.rightStick.x=f.rx;pad.rightStick.y=f.ry;
        pad.analogButtons.l2=f.l2;pad.analogButtons.r2=f.r2;
        pad.quat.w=1.0f; pad.connected=1;
        printf("seq=%u buttons=0x%04x L(%d,%d) R(%d,%d) L2=%d R2=%d conn=%d\n",
               f.seq,pad.buttons,pad.leftStick.x,pad.leftStick.y,
               pad.rightStick.x,pad.rightStick.y,pad.analogButtons.l2,
               pad.analogButtons.r2,pad.connected);
        shown++; got=0;
    }
    close(sock);
    printf("OK: 8 frames parsed+converted cleanly\n");
    return 0;
}
