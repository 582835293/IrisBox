/* IGB1 Guest graphic-buffer owner. Fullscreen has no CPU pixel access.
 * capture: one genuine Guest ScreenCaptureResult, retained until cleanup.
 * virtual: the original SF VirtualDisplay/BufferItemConsumer output only.
 * preview: one scaled JPEG for the list, never used by fullscreen.
 */
/* Guest原生帧所有者：通过自己的SurfaceFlinger虚拟显示取得buffer，发句柄和同步fence。
 * Host gateway交接连接，native Viewer显示并回ACK；协议字段见上层PROTOCOL.md。
 * virtual是全屏主线，preview才读像素编码JPEG；capture保留为单帧诊断，勿混为同一路。
 */
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/gui/BnScreenCaptureListener.h>
#include <binder/ProcessState.h>
#include <gui/BufferItemConsumer.h>
#include <gui/BufferQueue.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SyncScreenCaptureListener.h>
#include <ui/DisplayState.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>
#include <utils/String8.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <vector>

using namespace android;
static constexpr unsigned MAX_CONTROLLED_SECONDS = 900;
static volatile sig_atomic_t stopping = 0;
static uint64_t softDeadline = 0;
static timer_t operationTimer{};
static bool timerReady = false;
static void stopHandler(int) { stopping = 1; }
static void hardHandler(int) {
    const char message[] = "IGB_DEADLINE: producer exited itself; verify private display cleanup after Binder death\n";
    (void)write(STDERR_FILENO, message, sizeof(message) - 1);
    _exit(75);
}
static uint64_t nowNs() {
    timespec value{};if(clock_gettime(CLOCK_BOOTTIME,&value))return 0;
    return uint64_t(value.tv_sec)*1000000000ULL+uint64_t(value.tv_nsec);
}
static void armOperation(unsigned seconds=3) {
    if(!timerReady)return;itimerspec value{};value.it_value.tv_sec=seconds;
    (void)timer_settime(operationTimer,0,&value,nullptr);
}
static void disarmOperation() {
    if(!timerReady)return;itimerspec value{};(void)timer_settime(operationTimer,0,&value,nullptr);
}
static bool running() { return !stopping&&nowNs()<softDeadline; }
static uint32_t get32(const uint8_t* bytes) {
    return (uint32_t(bytes[0])<<24)|(uint32_t(bytes[1])<<16)|(uint32_t(bytes[2])<<8)|uint32_t(bytes[3]);
}
static uint64_t get64(const uint8_t* bytes) { return (uint64_t(get32(bytes))<<32)|get32(bytes+4); }
static void put32(uint8_t* bytes,uint32_t number) {
    for(int i=3;i>=0;--i){bytes[i]=uint8_t(number);number>>=8;}
}
static void put64(uint8_t* bytes,uint64_t number) {
    for(int i=7;i>=0;--i){bytes[i]=uint8_t(number);number>>=8;}
}
static bool integer(const char* text,uint64_t minimum,uint64_t maximum,uint64_t* result) {
    if(!text||!*text)return false;uint64_t value=0;
    for(const char* p=text;*p;++p){if(*p<'0'||*p>'9')return false;unsigned digit=unsigned(*p-'0');
        if(value>(maximum-digit)/10)return false;value=value*10+digit;}
    if(value<minimum||value>maximum)return false;*result=value;return true;
}
static bool parseSession(const char* text,uint64_t* pid,uint64_t* tick) {
    const std::string value(text?text:"");const auto colon=value.find(':');
    return colon!=std::string::npos&&value.find(':',colon+1)==std::string::npos&&
        integer(value.substr(0,colon).c_str(),2,4194304,pid)&&integer(value.substr(colon+1).c_str(),1,UINT64_MAX,tick);
}
static bool guestIdentity(uint64_t expectedTick) {
    FILE* command=fopen("/proc/1/cmdline","re");char name[512]{};
    if(!command)return false;const size_t bytes=fread(name,1,sizeof(name)-1,command);fclose(command);
    if(!bytes||!strstr(name,"/system/bin/iris_init")||getpid()==1)return false;
    FILE* stat=fopen("/proc/1/stat","re");char line[4096]{};
    if(!stat)return false;const bool read=fgets(line,sizeof(line),stat)!=nullptr;fclose(stat);if(!read)return false;
    char* tail=strrchr(line,')');if(!tail||tail[1]!=' ')return false;tail+=2;
    char* state=nullptr;char* field=strtok_r(tail," ",&state);unsigned index=0;
    while(field&&index<19){field=strtok_r(nullptr," ",&state);++index;}
    uint64_t actualTick=0;return field&&integer(field,1,UINT64_MAX,&actualTick)&&actualTick==expectedTick;
}
static int waitFd(int fd,short events,uint64_t deadline) {
    while(running()&&nowNs()<deadline) {
        const uint64_t current=nowNs();if(current>=std::min(deadline,softDeadline))return 0;
        const uint64_t remaining=std::min(deadline,softDeadline)-current;
        const int millis=int(std::min<uint64_t>(100,(remaining+999999)/1000000));
        pollfd descriptor{fd,events,0};const int result=poll(&descriptor,1,std::max(1,millis));
        if(result<0&&errno==EINTR)continue;
        if(result<0)return -1;
        if(result>0) {
            if(descriptor.revents&POLLNVAL)return -1;
            // A peer can close after queuing RELEASE/STOP. Consume readable
            // packets before treating its simultaneous HUP/ERR as disconnect.
            if((events&POLLIN)&&(descriptor.revents&POLLIN))return 1;
            if(descriptor.revents&(POLLHUP|POLLERR))return -1;
            if(descriptor.revents&events)return 1;
        }
    }
    return 0;
}
class CaptureListener final : public gui::BnScreenCaptureListener {
public:
    binder::Status onScreenCaptureCompleted(const gui::ScreenCaptureResults& result) override {
        std::lock_guard<std::mutex> lock(mutex);if(!complete){value=result;complete=true;}event.notify_all();return binder::Status::ok();
    }
    bool wait(gui::ScreenCaptureResults* result) {
        const uint64_t by=std::min<uint64_t>(softDeadline,nowNs()+3000000000ULL);
        std::unique_lock<std::mutex> lock(mutex);
        while(!complete&&running()&&nowNs()<by)event.wait_for(lock,std::chrono::milliseconds(50));
        if(!complete)return false;*result=value;return true;
    }
private:
    std::mutex mutex;std::condition_variable event;bool complete=false;gui::ScreenCaptureResults value;
};
class FrameListener final : public ConsumerBase::FrameAvailableListener {
public:
    void onFrameAvailable(const BufferItem&) override { notifications.fetch_add(1);event.notify_all(); }
    void onFrameReplaced(const BufferItem&) override { notifications.fetch_add(1);event.notify_all(); }
    void wait() { std::unique_lock<std::mutex> lock(mutex);event.wait_for(lock,std::chrono::milliseconds(20)); }
    std::atomic<uint64_t> notifications{0};
private:
    std::mutex mutex;std::condition_variable event;
};
struct Options {
    int id=0;uint64_t initPid=0,initTick=0;std::string session,path,mode="capture";
    uint32_t width=960,height=540;unsigned seconds=0;
};
static bool options(int argc,char** argv,Options* output) {
    uint64_t id=0;if(argc<4||!integer(argv[1],7,65535,&id)||!parseSession(argv[2],&output->initPid,&output->initTick))return false;
    output->id=int(id);output->session=argv[2];output->path=argv[3];
    const std::string expected="/data/iris_buffer/"+std::to_string(output->initPid)+"_"+std::to_string(output->initTick)+"/output.sock";
    if(output->path!=expected||output->path.size()>=sizeof(sockaddr_un{}.sun_path))return false;
    for(int i=4;i<argc;i+=2) {
        if(i+1>=argc)return false;uint64_t value=0;
        if(!strcmp(argv[i],"--mode")){output->mode=argv[i+1];if(output->mode!="capture"&&output->mode!="virtual"&&output->mode!="preview")return false;}
        else if(!strcmp(argv[i],"--seconds")&&integer(argv[i+1],0,MAX_CONTROLLED_SECONDS,&value)&&(value==0||value>=5))output->seconds=unsigned(value);
        else if(!strcmp(argv[i],"--width")&&integer(argv[i+1],320,1920,&value))output->width=uint32_t(value);
        else if(!strcmp(argv[i],"--height")&&integer(argv[i+1],180,1080,&value))output->height=uint32_t(value);
        else return false;
    }
    return uint64_t(output->width)*output->height<=2073600;
}
static bool previewWrite(int fd,const void* data,size_t size) {
    const char* p=static_cast<const char*>(data);
    while(size){ssize_t n=write(fd,p,size);if(n<0&&errno==EINTR)continue;if(n<=0)return false;p+=n;size-=size_t(n);}
    return true;
}
static int previewOnce(const Options& option) {
    if(getuid()!=0||!guestIdentity(option.initTick)||option.width!=640||option.height!=360)return 3;
    ProcessState::self()->setThreadPoolMaxThreadCount(0);ProcessState::self()->startThreadPool();
    auto id=SurfaceComposerClient::getInternalDisplayId();if(!id)return 4;
    gui::DisplayCaptureArgs args;args.displayToken=SurfaceComposerClient::getPhysicalDisplayToken(*id);
    args.width=option.width;args.height=option.height;if(!args.displayToken)return 4;
    sp<SyncScreenCaptureListener> listener=new SyncScreenCaptureListener();
    if(ScreenshotClient::captureDisplay(args,listener)!=NO_ERROR)return 5;
    const ScreenCaptureResults result=listener->waitForResults();
    if(result.result!=NO_ERROR||!result.buffer)return 5;
    sp<GraphicBuffer> buffer=result.buffer;void* pixels=nullptr;
    if(buffer->getWidth()!=640||buffer->getHeight()!=360||buffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN,&pixels)!=NO_ERROR||!pixels)return 5;
    AndroidBitmapInfo info{};auto format=buffer->getPixelFormat();
    if(format==PIXEL_FORMAT_RGB_565)info.format=ANDROID_BITMAP_FORMAT_RGB_565;
    else if(format==PIXEL_FORMAT_RGBA_8888||format==PIXEL_FORMAT_RGBX_8888)info.format=ANDROID_BITMAP_FORMAT_RGBA_8888;
    else {buffer->unlock();return 5;}
    info.flags=ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;info.width=640;info.height=360;info.stride=buffer->getStride()*bytesPerPixel(format);
    int fd=STDOUT_FILENO;
    int encoded=AndroidBitmap_compress(&info,int32_t(result.capturedDataspace),pixels,ANDROID_BITMAP_COMPRESS_FORMAT_JPEG,65,&fd,
        [](void* context,const void* bytes,size_t size){return previewWrite(*static_cast<int*>(context),bytes,size);});
    status_t unlocked=buffer->unlock();
    // JPEG writes and buffer unlock have completed. This one-shot process still
    // has Binder callbacks in flight; process-wide C++ teardown can destroy
    // their mutexes underneath those threads. Kernel exit reclaims its IPC
    // resources without racing a live Binder pool during static destruction.
    _exit(encoded==ANDROID_BITMAP_RESULT_SUCCESS&&unlocked==NO_ERROR ? 0 : 6);
}
static int listenSocket(const Options& option,int* ownerLock) {
    const std::string parent=option.path.substr(0,option.path.rfind('/'));struct stat directory{};
    if(lstat(parent.c_str(),&directory)||!S_ISDIR(directory.st_mode)||directory.st_uid!=0||(directory.st_mode&077)!=0) {
        fprintf(stderr,"IGB_ERROR private parent directory must be root-owned 0700\n");return -1;
    }
    *ownerLock=open((parent+"/producer.lock").c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
    if(*ownerLock<0)return -1;
    if(flock(*ownerLock,LOCK_EX|LOCK_NB)){close(*ownerLock);*ownerLock=-1;fprintf(stderr,"IGB_ERROR another producer owns this session output\n");return -1;}
    struct stat previous{};
    if(!lstat(option.path.c_str(),&previous)) {
        if(!S_ISSOCK(previous.st_mode)||previous.st_uid!=0||unlink(option.path.c_str()))return -1;
    }else if(errno!=ENOENT)return -1;
    int server=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0);if(server<0)return -1;
    sockaddr_un address{};address.sun_family=AF_UNIX;memcpy(address.sun_path,option.path.c_str(),option.path.size()+1);
    const socklen_t length=socklen_t(offsetof(sockaddr_un,sun_path)+option.path.size()+1);
    if(bind(server,reinterpret_cast<sockaddr*>(&address),length)||chmod(option.path.c_str(),0600)||listen(server,1)) {
        close(server);unlink(option.path.c_str());return -1;
    }
    printf("IGB_LISTEN {\"id\":%d,\"session\":\"%s\",\"mode\":\"%s\",\"socketMode\":\"0600\",\"maxSeconds\":%u}\n",option.id,option.session.c_str(),option.mode.c_str(),option.seconds);fflush(stdout);
    return server;
}
static int acceptPeer(int server) {
    const uint64_t by=std::min<uint64_t>(softDeadline,nowNs()+30000000000ULL);
    for(unsigned count=0;count<5&&running();++count) {
        if(waitFd(server,POLLIN,by)!=1)return -1;
        int peer=accept4(server,nullptr,nullptr,SOCK_CLOEXEC);if(peer<0)return -1;
        ucred credential{};socklen_t size=sizeof(credential);
        if(getsockopt(peer,SOL_SOCKET,SO_PEERCRED,&credential,&size)||size!=sizeof(credential)||credential.uid!=0){close(peer);continue;}
        // Host PID may be 0 when viewed inside the Guest's PID namespace.
        timeval timeout{2,0};
        if(setsockopt(peer,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout))||setsockopt(peer,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))){close(peer);return -1;}
        printf("IGB_PEER {\"uid\":%u,\"namespacePid\":%d}\n",credential.uid,credential.pid);fflush(stdout);return peer;
    }
    return -1;
}
struct Ack {uint32_t kind=0;uint64_t sequence=0;int fence=-1;};
/* SEQPACKET保持消息边界；ACK kind=1 PRESENT，2 RELEASE，3 STOP。
 * 校验大小、序号及附带FD数量，错误也必须close收到的FD，避免泄漏。
 */
static int receiveAck(int peer,Ack* ack,bool block) {
    const uint64_t by=std::min<uint64_t>(softDeadline,nowNs()+(block?3000000000ULL:0));
    if(block){const int ready=waitFd(peer,POLLIN,by);if(ready<=0)return ready;}
    std::array<uint8_t,24> packet{};std::array<char,CMSG_SPACE(sizeof(int)*8)> control{};
    iovec vector{packet.data(),packet.size()};msghdr message{};message.msg_iov=&vector;message.msg_iovlen=1;
    message.msg_control=control.data();message.msg_controllen=control.size();
    const ssize_t bytes=recvmsg(peer,&message,MSG_DONTWAIT|MSG_CMSG_CLOEXEC);
    if(bytes<0&&(errno==EAGAIN||errno==EWOULDBLOCK))return block?-1:0;
    if(bytes<0&&errno==EINTR)return running()?0:-1;
    std::vector<int> fds;bool unexpected=false;
    for(cmsghdr* item=CMSG_FIRSTHDR(&message);item;item=CMSG_NXTHDR(&message,item)) {
        if(item->cmsg_level!=SOL_SOCKET||item->cmsg_type!=SCM_RIGHTS||item->cmsg_len<CMSG_LEN(0)){unexpected=true;continue;}
        const size_t count=(item->cmsg_len-CMSG_LEN(0))/sizeof(int);auto* values=reinterpret_cast<int*>(CMSG_DATA(item));
        for(size_t i=0;i<count;++i)fds.push_back(values[i]);
    }
    bool valid=bytes==24&&!(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC))&&!unexpected&&fds.size()<=1&&
        get32(packet.data())==0x49474131&&get32(packet.data()+4)==1&&get32(packet.data()+12)==0;
    const uint32_t kind=get32(packet.data()+8);const uint64_t sequence=get64(packet.data()+16);
    valid=valid&&(kind>=1&&kind<=3)&&((kind==3&&sequence==0&&fds.empty())||(kind!=3&&sequence>0));
    if(!valid){for(int fd:fds)close(fd);return -1;}
    ack->kind=kind;ack->sequence=sequence;ack->fence=fds.empty()?-1:fds[0];return 1;
}
static void describeBuffer(const sp<GraphicBuffer>& buffer,uint64_t sequence,uint64_t frameNumber,int slot) {
    AHardwareBuffer_Desc desc{};AHardwareBuffer_describe(buffer->toAHardwareBuffer(),&desc);uint64_t ahbId=0;
    const int idResult=AHardwareBuffer_getId(buffer->toAHardwareBuffer(),&ahbId);
    const native_handle_t* handle=buffer->handle;
    printf("IGB_BUFFER {\"seq\":%llu,\"frameNumber\":%llu,\"slot\":%d,\"graphicId\":%llu,\"ahbId\":%llu,\"ahbIdResult\":%d,"
        "\"width\":%u,\"height\":%u,\"stride\":%u,\"layers\":%u,\"format\":%u,\"usage\":%llu,\"numFds\":%d,\"numInts\":%d}\n",
        (unsigned long long)sequence,(unsigned long long)frameNumber,slot,(unsigned long long)buffer->getId(),(unsigned long long)ahbId,idResult,
        desc.width,desc.height,desc.stride,desc.layers,desc.format,(unsigned long long)desc.usage,handle?handle->numFds:0,handle?handle->numInts:0);
    if(handle&&sequence<=4)for(int index=0;index<handle->numFds&&index<16;++index) {
        const int fd=handle->data[index];struct stat status{};const int result=fstat(fd,&status);
        char path[80]{},target[256]{};snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);const ssize_t length=readlink(path,target,sizeof(target)-1);
        if(length>=0)target[length]=0;
        printf("IGB_BUFFER_FD {\"seq\":%llu,\"index\":%d,\"fd\":%d,\"fstat\":%d,\"dev\":%llu,\"inode\":%llu,\"size\":%lld,\"mode\":%u,\"target\":\"%s\"}\n",
            (unsigned long long)sequence,index,fd,result,(unsigned long long)status.st_dev,(unsigned long long)status.st_ino,(long long)status.st_size,unsigned(status.st_mode),target);
    }
    fflush(stdout);
}
/* 先发96字节IGB头与acquire fence，再发AHardwareBuffer handle，不复制整帧像素。
 * 尺寸/裁剪/格式/方向/序号必须配套；不能把有buffer FD等同于Host必然正确导入。
 */
static bool sendFrame(int peer,const Options& option,const sp<GraphicBuffer>& buffer,const sp<Fence>& acquire,
        uint64_t sequence,uint32_t displayWidth,uint32_t displayHeight,int32_t dataspace,Rect crop,uint32_t transform,
        uint64_t frameNumber,int slot,uint32_t rotation) {
    if(!buffer||buffer->initCheck()!=OK)return false;
    AHardwareBuffer* ahb=buffer->toAHardwareBuffer();AHardwareBuffer_Desc desc{};AHardwareBuffer_describe(ahb,&desc);
    if(!desc.width||!desc.height||desc.width>1920||desc.height>1920||uint64_t(desc.width)*desc.height>2073600||desc.layers!=1||
        !(desc.usage&AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE))return false;
    if(crop.isEmpty())crop=Rect(desc.width,desc.height);
    if(crop.left<0||crop.top<0||crop.right>int32_t(desc.width)||crop.bottom>int32_t(desc.height))return false;
    std::array<uint8_t,96> header{};
    put32(header.data(),0x49474231);put32(header.data()+4,2);put32(header.data()+8,uint32_t(option.id));put32(header.data()+12,uint32_t(option.initPid));
    put64(header.data()+16,option.initTick);put32(header.data()+24,desc.width);put32(header.data()+28,desc.height);
    put32(header.data()+32,displayWidth);put32(header.data()+36,displayHeight);put32(header.data()+40,desc.format);put32(header.data()+44,uint32_t(dataspace));
    put64(header.data()+48,sequence);put64(header.data()+56,nowNs());
    put32(header.data()+64,uint32_t(crop.left));put32(header.data()+68,uint32_t(crop.top));put32(header.data()+72,uint32_t(crop.right));put32(header.data()+76,uint32_t(crop.bottom));
    put32(header.data()+80,transform);put32(header.data()+84,(option.mode=="virtual"?1U:0U)|(rotation<<1));put64(header.data()+88,buffer->getId());
    const int fence=acquire&&acquire->isValid()?acquire->dup():-1;
    if(acquire&&acquire->isValid()&&fence<0)return false;
    std::array<char,CMSG_SPACE(sizeof(int))> control{};iovec vector{header.data(),header.size()};msghdr message{};
    message.msg_iov=&vector;message.msg_iovlen=1;
    if(fence>=0){message.msg_control=control.data();message.msg_controllen=control.size();cmsghdr* item=CMSG_FIRSTHDR(&message);
        item->cmsg_level=SOL_SOCKET;item->cmsg_type=SCM_RIGHTS;item->cmsg_len=CMSG_LEN(sizeof(int));memcpy(CMSG_DATA(item),&fence,sizeof(fence));}
    const bool writable=waitFd(peer,POLLOUT,std::min<uint64_t>(softDeadline,nowNs()+2000000000ULL))==1;
    const ssize_t sent=writable?sendmsg(peer,&message,MSG_NOSIGNAL|MSG_DONTWAIT):-1;
    if(fence>=0)close(fence);if(sent!=ssize_t(header.size()))return false;
    armOperation();const int result=AHardwareBuffer_sendHandleToUnixSocket(ahb,peer);disarmOperation();
    if(result!=0){fprintf(stderr,"IGB_ERROR AHB send result=%d\n",result);return false;}
    if(sequence<=4||sequence%120==0)describeBuffer(buffer,sequence,frameNumber,slot);
    return true;
}
class Producer {
public:
    explicit Producer(const Options& options):option(options){}
    ~Producer(){cleanup();}
    int run() {
        if(getuid()!=0||!guestIdentity(option.initTick)){fprintf(stderr,"IGB_ERROR verified Guest PID1 iris_init/starttick required\n");return 3;}
        server=listenSocket(option,&ownerLock);if(server<0){perror("IGB listen");return 4;}
        peer=acceptPeer(server);if(peer<0){fprintf(stderr,"IGB_ERROR no valid root peer before deadline\n");return 5;}
        close(server);server=-1;
        armOperation();ProcessState::self()->setThreadPoolMaxThreadCount(2);ProcessState::self()->startThreadPool();
        const auto id=SurfaceComposerClient::getInternalDisplayId();
        if(!id){disarmOperation();return 6;}
        sourceToken=SurfaceComposerClient::getPhysicalDisplayToken(*id);
        const status_t state=SurfaceComposerClient::getDisplayState(sourceToken,&source);disarmOperation();
        if(state!=OK||source.layerStackSpaceRect.getWidth()<=0||source.layerStackSpaceRect.getHeight()<=0)return 6;
        logicalWidth=uint32_t(source.layerStackSpaceRect.getWidth());logicalHeight=uint32_t(source.layerStackSpaceRect.getHeight());
        printf("IGB_SOURCE {\"logicalWidth\":%u,\"logicalHeight\":%u,\"layerStack\":%u,\"orientation\":%u,\"captureCalls\":0,\"pixelLockCalls\":0}\n",
            logicalWidth,logicalHeight,source.layerStack.id,unsigned(source.orientation));fflush(stdout);
        if(option.mode=="capture")return capture();return virtualOutput();
    }
    void report(int result) const {
        printf("IGB_SUMMARY {\"id\":%d,\"session\":\"%s\",\"mode\":\"%s\",\"sentFrames\":%llu,\"presentAcks\":%llu,"
            "\"releaseAcks\":%llu,\"maxAcquiredInFlight\":%zu,\"captureCalls\":%llu,\"pixelLockCalls\":0,\"pixelCopyBytes\":0,"
            "\"lastFrameNumber\":%llu,\"notifications\":%llu,\"result\":%d}\n",option.id,option.session.c_str(),option.mode.c_str(),
            (unsigned long long)sentFrames,(unsigned long long)presentAcks,(unsigned long long)releaseAcks,maximumInFlight,
            (unsigned long long)captureCalls,(unsigned long long)lastFrameNumber,(unsigned long long)(listener?listener->notifications.load():0),result);fflush(stdout);
    }
private:
    Options option;int server=-1,peer=-1,ownerLock=-1;sp<IBinder> sourceToken,displayToken;ui::DisplayState source;
    sp<IGraphicBufferProducer> endpoint;sp<IGraphicBufferConsumer> rawConsumer;sp<BufferItemConsumer> output;sp<FrameListener> listener;
    sp<GraphicBuffer> captureOwner;struct Pending { BufferItem item; sp<BufferItemConsumer> owner; };
    std::map<uint64_t,Pending> inFlight;
    uint32_t logicalWidth=0,logicalHeight=0;uint64_t sentFrames=0,presentAcks=0,releaseAcks=0,captureCalls=0,lastFrameNumber=0;size_t maximumInFlight=0;
    bool captureReleased=false,cleaned=false,requestedStop=false;
    int capture() {
        sp<CaptureListener> callback=new CaptureListener();gui::DisplayCaptureArgs args;args.displayToken=sourceToken;
        args.width=option.width;args.height=option.height;args.pixelFormat=ui::PixelFormat::RGBA_8888;args.dataspace=ui::Dataspace::V0_SRGB;
        armOperation();++captureCalls;const status_t requested=ScreenshotClient::captureDisplay(args,callback);disarmOperation();
        if(requested!=OK)return 7;gui::ScreenCaptureResults result;if(!callback->wait(&result)||result.result!=OK||!result.buffer)return 7;
        captureOwner=result.buffer;maximumInFlight=1;
        if(!sendFrame(peer,option,captureOwner,result.fence,1,logicalWidth,logicalHeight,int32_t(result.capturedDataspace),Rect(),0,0,-1,unsigned(source.orientation)))return 8;
        sentFrames=1;
        while(running()&&!requestedStop){Ack ack;const int received=receiveAck(peer,&ack,true);if(received<0)return 9;if(received==0)continue;
            if(!consume(ack))return 9;}
        return 0;
    }
    /* 最多持有两帧等待Host释放，防接收端慢时无限积累。
     * source尺寸/方向变化建立新队列，旧帧仍记住旧consumer，不能误还给新队列。
     */
    int virtualOutput() {
        if(!configureVirtual(source))return 10;
        status_t result=OK;uint64_t nextCheck=0;
        while(running()&&!requestedStop) {
            for(unsigned count=0;count<8;++count) {
                Ack ack;const int received=receiveAck(peer,&ack,false);
                if(received<0)return 11;if(received==0)break;if(!consume(ack))return 11;
                // STOP is a terminal command. A following recv could observe
                // the peer's EOF and incorrectly turn this normal stop into 11.
                if(requestedStop)break;
            }
            if(requestedStop||!running())break;
            // A fresh queue on each source geometry change prevents an old
            // queued buffer from being labelled with the new orientation.
            if(nowNs()>=nextCheck) {
                nextCheck=nowNs()+100000000ULL;ui::DisplayState next;
                armOperation();result=SurfaceComposerClient::getDisplayState(sourceToken,&next);disarmOperation();
                if(result!=OK)return 10;
                if(next.orientation!=source.orientation||next.layerStack!=source.layerStack||
                    next.layerStackSpaceRect!=source.layerStackSpaceRect) {
                    if(!configureVirtual(next))return 10;
                }
            }
            if(inFlight.size()>=2){Ack ack;const int received=receiveAck(peer,&ack,true);if(received<0||(received==0&&running()))return 11;if(received>0&&!consume(ack))return 11;continue;}
            BufferItem item;armOperation();result=output->acquireBuffer(&item,0,false);disarmOperation();
            if(result==BufferItemConsumer::NO_BUFFER_AVAILABLE){listener->wait();continue;}
            if(result!=OK||!item.mGraphicBuffer){fprintf(stderr,"IGB_ERROR acquire result=%d\n",result);return 12;}
            if(item.mFrameNumber<=lastFrameNumber){inFlight.emplace(sentFrames+1,Pending{item,output});fprintf(stderr,"IGB_ERROR repeated frameNumber\n");return 12;}
            const uint64_t sequence=sentFrames+1;inFlight.emplace(sequence,Pending{item,output});maximumInFlight=std::max(maximumInFlight,inFlight.size());
            lastFrameNumber=item.mFrameNumber;
            if(!sendFrame(peer,option,item.mGraphicBuffer,item.mFence,sequence,logicalWidth,logicalHeight,int32_t(item.mDataSpace),item.mCrop,item.mTransform,item.mFrameNumber,item.mSlot,unsigned(source.orientation)))return 13;
            sentFrames=sequence;
        }
        return 0;
    }
    /* 原始layer-stack坐标已跟随应用方向，投影用ROTATION_0，避免再转一次。
     * 竖屏同步交换输出宽高；只在纵横比匹配时接受，不靠Host缩小掩盖Guest裁剪。
     */
    bool configureVirtual(const ui::DisplayState& next) {
        const uint32_t w=next.layerStackSpaceRect.getWidth(),h=next.layerStackSpaceRect.getHeight();
        uint32_t outW=option.width,outH=option.height;
        if(w<h)std::swap(outW,outH);
        if(!w||!h||unsigned(next.orientation)>3||uint64_t(w)*outH!=uint64_t(h)*outW) {
            fprintf(stderr,"IGB_ERROR unsupported source aspect %ux%u\n",w,h);return false;
        }
        sp<IGraphicBufferProducer> newEndpoint;sp<IGraphicBufferConsumer> newRaw;
        armOperation();BufferQueue::createBufferQueue(&newEndpoint,&newRaw);disarmOperation();
        if(!newEndpoint||!newRaw)return false;
        armOperation();sp<BufferItemConsumer> newOutput=new BufferItemConsumer(newRaw,AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,2,false);
        status_t result=newRaw->setMaxBufferCount(5);
        if(result==OK)result=newEndpoint->setMaxDequeuedBufferCount(1);
        if(result==OK)result=newOutput->setDefaultBufferSize(outW,outH);
        if(result==OK)result=newOutput->setDefaultBufferFormat(PIXEL_FORMAT_RGBA_8888);
        if(result==OK)result=newOutput->setDefaultBufferDataSpace(HAL_DATASPACE_V0_SRGB);
        newOutput->setName(String8::format("IrisBufferOutput-%d",option.id));
        sp<FrameListener> newListener=new FrameListener();newOutput->setFrameAvailableListener(newListener);disarmOperation();
        if(result!=OK)return false;
        armOperation();sp<IBinder> newDisplay=SurfaceComposerClient::createDisplay(String8::format("IrisBuffer-%d-%llu",option.id,(unsigned long long)option.initPid),false);
        if(!newDisplay){disarmOperation();return false;}
        SurfaceComposerClient::Transaction tx;result=tx.setDisplaySurface(newDisplay,newEndpoint);
        tx.setDisplayLayerStack(newDisplay,next.layerStack);
        // Source layer-stack coordinates already follow the app orientation.
        tx.setDisplayProjection(newDisplay,ui::ROTATION_0,Rect(next.layerStackSpaceRect),Rect(outW,outH));
        tx.setDisplaySize(newDisplay,outW,outH);if(result==OK)result=tx.apply(true);
        if(result!=OK){SurfaceComposerClient::destroyDisplay(newDisplay);disarmOperation();return false;}
        if(displayToken)SurfaceComposerClient::destroyDisplay(displayToken);disarmOperation();
        // Pending frames retain their original consumer until the Host returns
        // its release fence. Never return them to the replacement queue.
        displayToken=newDisplay;endpoint=newEndpoint;rawConsumer=newRaw;output=newOutput;listener=newListener;
        source=next;logicalWidth=w;logicalHeight=h;lastFrameNumber=0;
        printf("IGB_VIRTUAL {\"created\":true,\"maxAcquired\":2,\"maxQueueBuffers\":5,\"width\":%u,\"height\":%u,\"logicalWidth\":%u,\"logicalHeight\":%u,\"rotation\":%u,\"captureCalls\":0}\n",outW,outH,w,h,unsigned(source.orientation));fflush(stdout);
        return true;
    }
    /* PRESENT只是显示完成通知，不能据此复用buffer；只有RELEASE才把release fence还给原consumer。
     * fence约束Host使用结束的时刻，不得伪造NO_FENCE以绕过等待。
     */
    bool consume(Ack& ack) {
        if(ack.kind==3){requestedStop=true;return true;}
        if(ack.sequence>sentFrames){if(ack.fence>=0)close(ack.fence);return false;}
        if(ack.kind==1){++presentAcks;if(ack.fence>=0)close(ack.fence);return true;}
        if(option.mode=="capture") {
            if(ack.sequence!=1||captureReleased){if(ack.fence>=0)close(ack.fence);return false;}
            captureReleased=true;++releaseAcks;if(ack.fence>=0)close(ack.fence);return true;
        }
        const auto entry=inFlight.find(ack.sequence);if(entry==inFlight.end()){if(ack.fence>=0)close(ack.fence);return false;}
        sp<Fence> release=ack.fence>=0?new Fence(ack.fence):Fence::NO_FENCE;ack.fence=-1;
        if(release->isValid()&&release->getStatus()==Fence::Status::Invalid){fprintf(stderr,"IGB_ERROR invalid release fence\n");return false;}
        armOperation();const status_t result=entry->second.owner->releaseBuffer(entry->second.item,release);disarmOperation();
        if(result!=OK){fprintf(stderr,"IGB_ERROR releaseBuffer result=%d\n",result);return false;}
        inFlight.erase(entry);++releaseAcks;return true;
    }
    /* 异常时Host可能仍在使用帧，先销毁私有显示并abandon队列，而不是归还不确定帧给活跃producer。
     */
    void cleanup() {
        if(cleaned)return;cleaned=true;
        // Never hand an uncertain Host-used item back to a live producer. Stop
        // private display first; abandonment prevents subsequent overwrites.
        const bool hadDisplay=displayToken!=nullptr,hadQueue=output!=nullptr;
        if(displayToken){armOperation();SurfaceComposerClient::destroyDisplay(displayToken);disarmOperation();displayToken.clear();}
        if(output){armOperation();output->abandon();disarmOperation();}
        const size_t abandoned=inFlight.size();inFlight.clear();captureOwner.clear();
        output.clear();listener.clear();endpoint.clear();rawConsumer.clear();sourceToken.clear();
        if(peer>=0){close(peer);peer=-1;}if(server>=0){close(server);server=-1;}
        if(ownerLock>=0){struct stat socketState{};if(!lstat(option.path.c_str(),&socketState)&&S_ISSOCK(socketState.st_mode)&&socketState.st_uid==0)unlink(option.path.c_str());close(ownerLock);ownerLock=-1;}
        printf("IGB_CLEANUP {\"hadDisplay\":%s,\"displayDestroyCallReturned\":%s,\"hadQueue\":%s,\"queueAbandonCallReturned\":%s,\"uncertainItemsAbandoned\":%zu,\"returnedWithoutReleaseFence\":0}\n",
            hadDisplay?"true":"false",hadDisplay?"true":"false",hadQueue?"true":"false",hadQueue?"true":"false",abandoned);fflush(stdout);
    }
};
int main(int argc,char** argv) {
    Options option;if(!options(argc,argv,&option)){fprintf(stderr,"usage: iris_buffer_producer 7|8 PID:TICK /data/iris_buffer/PID_TICK/output.sock --mode capture|virtual|preview --seconds 0|5..900 --width 960 --height 540\n");return 2;}
    signal(SIGTERM,stopHandler);signal(SIGINT,stopHandler);signal(SIGHUP,stopHandler);signal(SIGPIPE,SIG_IGN);signal(SIGALRM,hardHandler);
    sigevent event{};event.sigev_notify=SIGEV_SIGNAL;event.sigev_signo=SIGALRM;
    if(timer_create(CLOCK_MONOTONIC,&event,&operationTimer)){perror("IGB operation timer");return 3;}timerReady=true;
    if(option.mode=="preview"){
        armOperation();int result=previewOnce(option);disarmOperation();timer_delete(operationTimer);_exit(result);
    }
    alarm(option.seconds);softDeadline=option.seconds==0 ? UINT64_MAX : nowNs()+uint64_t(option.seconds-2)*1000000000ULL;
    int result=0;{Producer producer(option);result=producer.run();producer.report(result);}
    alarm(0);disarmOperation();timer_delete(operationTimer);return result;
}
