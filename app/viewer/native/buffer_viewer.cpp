/* Host JNI缓冲区接收端，由独立DisplayActivity进程启动；不在此渲染Guest应用。
 * 接IGB头/原生handle → 导入AHardwareBuffer → SurfaceControl事务显示 → 回PRESENT/RELEASE。
 * reader接帧，sender发送ACK，Binder回调只提取状态，watchdog约束阻塞操作。
 * Buffer生命周期、fence和显示几何三者配套，不能把“直接显示内存”理解为跳过同步。
 */
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/log.h>
#include <sys/socket.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <time.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <thread>

namespace {
constexpr const char* TAG = "IrisBufferViewer";
constexpr size_t MAX_FDS = 128, MAX_AHB_PACKET = 16384;
uint64_t nowNs() { timespec t{}; clock_gettime(CLOCK_BOOTTIME, &t); return uint64_t(t.tv_sec)*1000000000ULL+t.tv_nsec; }
uint32_t be32(const uint8_t* p) { return uint32_t(p[0])<<24|uint32_t(p[1])<<16|uint32_t(p[2])<<8|p[3]; }
uint64_t be64(const uint8_t* p) { return uint64_t(be32(p))<<32|be32(p+4); }
void put32(uint8_t* p,uint32_t v) { for(int i=3;i>=0;--i){p[i]=v&255;v>>=8;} }
void put64(uint8_t* p,uint64_t v) { put32(p,uint32_t(v>>32));put32(p+4,uint32_t(v)); }
void error(const char* message) { __android_log_print(ANDROID_LOG_ERROR,TAG,"IRIS_BUFFER_ERROR %s errno=%d",message,errno); }
[[noreturn]] void fatal(const char* message,int code) { error(message); _exit(code); }
bool waitFd(int fd,short events,int ms) { pollfd p{fd,events,0};int n;do{n=poll(&p,1,ms);}while(n<0&&errno==EINTR);return n>0&&(p.revents&events); }

/* SCM_RIGHTS及MSG_PEEK都会取得FD引用；析构关闭未转移者，takeFence明确转交所有权。
 */
struct Packet {
    uint8_t data[MAX_AHB_PACKET]{};
    size_t size=0;
    int fds[MAX_FDS]{};
    size_t fdCount=0;
    ~Packet(){for(size_t i=0;i<fdCount;++i)if(fds[i]>=0)close(fds[i]);}
    int takeFence(){if(fdCount==0)return -1;int result=fds[0];fds[0]=-1;return result;}
};
bool packet(int fd,Packet& result,size_t limit,bool peek) {
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int)*MAX_FDS)]{};
    iovec io{result.data,limit};msghdr m{};m.msg_iov=&io;m.msg_iovlen=1;m.msg_control=control;m.msg_controllen=sizeof(control);
    ssize_t n=recvmsg(fd,&m,MSG_CMSG_CLOEXEC|(peek?MSG_PEEK:0));
    result.size=n>0?size_t(n):0;
    bool valid=n>0&&(m.msg_flags&(MSG_TRUNC|MSG_CTRUNC))==0;
    for(cmsghdr* c=CMSG_FIRSTHDR(&m);c;c=CMSG_NXTHDR(&m,c)) {
        if(c->cmsg_level!=SOL_SOCKET||c->cmsg_type!=SCM_RIGHTS||c->cmsg_len<CMSG_LEN(0)||(c->cmsg_len-CMSG_LEN(0))%sizeof(int)) { valid=false;continue; }
        size_t count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);int* f=reinterpret_cast<int*>(CMSG_DATA(c));
        for(size_t i=0;i<count;++i){if(result.fdCount<MAX_FDS)result.fds[result.fdCount++]=f[i];else{close(f[i]);valid=false;}}
    }
    return valid;
}

// AOSP13 AHardwareBuffer_recv does not expose its recvmsg truncation flags.
// Strictly inspect the next packet first; close every descriptor duplicated by MSG_PEEK.
bool preflightAHB(int fd,uint32_t width,uint32_t height,uint32_t format) {
    Packet p;
    if(!packet(fd,p,MAX_AHB_PACKET,true)||p.size<52||p.fdCount<1)return false;
    uint32_t words[13]{};memcpy(words,p.data,sizeof(words));
    if(words[0]!=0x47423031U||words[1]!=width||words[2]!=height||words[3]<width||words[4]!=format||words[5]!=1)return false;
    uint32_t nfds=words[10],nints=words[11];
    return nfds==p.fdCount&&nfds<=MAX_FDS&&nints<=4096-13&&p.size==(13+size_t(nints))*4;
}

struct Header {uint32_t width,height,displayWidth,displayHeight,format,dataspace,transform,flags;uint64_t sequence,pts,bufferId;ARect crop;};
struct Ack {uint32_t kind;uint64_t seq;int fence;};
struct State;
struct Completion {std::shared_ptr<State> owner;uint64_t sequence,previous;bool clear;Header frame{};ARect destination{};int viewWidth=0,viewHeight=0;};
struct State:std::enable_shared_from_this<State> {
    int fd=-1,id=0,pid=0;uint64_t tick=0,deadline=0;
    ANativeWindow* window=nullptr;ASurfaceControl* layer=nullptr;
    std::atomic<bool> stopping{false},finished{false},senderStopping{false};
    std::atomic<uint64_t> operationAt{0};
    std::atomic<int> viewWidth{0},viewHeight{0};
    std::mutex mu,ackMu;std::condition_variable completed,ackChanged;
    std::deque<Ack> acks;std::map<uint64_t,AHardwareBuffer*> buffers;std::deque<uint64_t> retired;
    uint64_t submitted=0,completedSequence=0,received=0,presented=0,uniqueLatch=0,lastLatch=0,withFence=0,lastPts=0,lastLog=0,sampledReceived=0,sampledPresented=0;
    bool clearComplete=false;std::string failure;
    Header visibleFrame{};ARect visibleDestination{};int visibleViewWidth=0,visibleViewHeight=0;uint64_t visibleSequence=0;
    std::thread reader,sender,watchdog;
    ~State(){for(auto& item:buffers)AHardwareBuffer_release(item.second);if(layer)ASurfaceControl_release(layer);if(window)ANativeWindow_release(window);if(fd>=0)close(fd);}
    void stop(const char* why){stopping=true;{std::lock_guard<std::mutex> l(mu);if(failure.empty()&&why)failure=why;}completed.notify_all();ackChanged.notify_all();}
    void queue(uint32_t kind,uint64_t seq,int fence) {
        bool overflow=false;
        {std::lock_guard<std::mutex> l(ackMu);if(acks.size()>=8||senderStopping){if(fence>=0)close(fence);overflow=true;}else acks.push_back(Ack{kind,seq,fence});}
        if(overflow)stop("bounded ACK queue unavailable");else ackChanged.notify_all();
    }
    /* 事务完成回调要区分当前帧PRESENT和上一帧RELEASE，fence各自回传。
     * 回调不等fence/不做socket I/O；几何在这里才变为visible，提交时还不能用于输入。
     */
    static void onComplete(void* context,ASurfaceTransactionStats* stats) {
        std::unique_ptr<Completion> c(static_cast<Completion*>(context));auto s=c->owner;
        // Stats are stack-lifetime data. Extract owned duplicated fences now; never
        // wait for a fence or perform socket I/O on this Binder callback thread.
        int present=c->clear?-1:ASurfaceTransactionStats_getPresentFenceFd(stats);
        ASurfaceControl** controls=nullptr;size_t count=0;
        ASurfaceTransactionStats_getASurfaceControls(stats,&controls,&count);
        bool contains=false;for(size_t i=0;i<count;++i)if(controls[i]==s->layer)contains=true;
        if(controls)ASurfaceTransactionStats_releaseASurfaceControls(controls);
        if(!contains){if(present>=0)close(present);s->stop("Transaction stats missing expected SurfaceControl; no release is acknowledged");return;}
        int release=c->previous?ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats,s->layer):-1;
        int64_t latch=ASurfaceTransactionStats_getLatchTime(stats);
        {std::lock_guard<std::mutex> l(s->mu);
            if(!c->clear){s->visibleFrame=c->frame;s->visibleDestination=c->destination;s->visibleViewWidth=c->viewWidth;s->visibleViewHeight=c->viewHeight;s->visibleSequence=c->sequence;++s->presented;if(present>=0)++s->withFence;if(latch>0&&uint64_t(latch)!=s->lastLatch){++s->uniqueLatch;s->lastLatch=uint64_t(latch);}}
            if(c->previous)s->retired.push_back(c->previous);
        }
        if(!c->clear)s->queue(1,c->sequence,present);
        if(c->previous)s->queue(2,c->previous,release);
        {std::lock_guard<std::mutex> l(s->mu);if(c->clear)s->clearComplete=true;else s->completedSequence=c->sequence;}
        s->completed.notify_all();
    }
    void releaseRetired() {
        std::deque<uint64_t> old;
        {std::lock_guard<std::mutex> l(mu);old.swap(retired);}
        for(uint64_t seq:old){auto found=buffers.find(seq);if(found!=buffers.end()){AHardwareBuffer_release(found->second);buffers.erase(found);}}
    }
    bool header(Packet& p,Header& h,uint64_t previous) {
        if(p.size!=96||p.fdCount>1||be32(p.data)!=0x49474231||be32(p.data+4)!=2||be32(p.data+8)!=uint32_t(id)||be32(p.data+12)!=uint32_t(pid)||be64(p.data+16)!=tick)return false;
        h.width=be32(p.data+24);h.height=be32(p.data+28);h.displayWidth=be32(p.data+32);h.displayHeight=be32(p.data+36);h.format=be32(p.data+40);h.dataspace=be32(p.data+44);
        h.sequence=be64(p.data+48);h.pts=be64(p.data+56);h.crop={int32_t(be32(p.data+64)),int32_t(be32(p.data+68)),int32_t(be32(p.data+72)),int32_t(be32(p.data+76))};
        h.transform=be32(p.data+80);h.flags=be32(p.data+84);h.bufferId=be64(p.data+88);
        if(h.width==0||h.height==0||h.width>4096||h.height>4096||uint64_t(h.width)*h.height>16777216||h.displayWidth==0||h.displayHeight==0||h.displayWidth>4096||h.displayHeight>4096||h.format!=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM||h.sequence<=previous||h.pts==0||h.pts>nowNs()+1000000000ULL||nowNs()-std::min(nowNs(),h.pts)>4000000000ULL||h.transform>7||h.flags>7)return false;
        if(h.crop.left>=h.crop.right||h.crop.top>=h.crop.bottom)h.crop={0,0,int32_t(h.width),int32_t(h.height)};
        return h.crop.left>=0&&h.crop.top>=0&&h.crop.right<=int32_t(h.width)&&h.crop.bottom<=int32_t(h.height);
    }
    /* 按裁剪后的纵横比居中留黑边，不拉伸；setBuffer接管acquire FD。
     * 事务完成计数不是承诺每帧显示或固定FPS，性能须用独立时间窗口/负载观察。
     */
    bool display(AHardwareBuffer* buffer,int acquire,const Header& h) {
        int vw=viewWidth.load(),vh=viewHeight.load();if(vw<=0||vh<=0){if(acquire>=0)close(acquire);return false;}
        uint64_t previous=submitted;buffers.emplace(h.sequence,buffer);submitted=h.sequence;
        int cw=h.crop.right-h.crop.left,ch=h.crop.bottom-h.crop.top;if(h.transform&4)std::swap(cw,ch);
        float scale=std::min(vw/float(cw),vh/float(ch));int dw=int(cw*scale),dh=int(ch*scale);ARect dest{(vw-dw)/2,(vh-dh)/2,(vw+dw)/2,(vh+dh)/2};
        ASurfaceTransaction* tx=ASurfaceTransaction_create();
        ASurfaceTransaction_setVisibility(tx,layer,ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_setBufferTransparency(tx,layer,ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setGeometry(tx,layer,h.crop,dest,int32_t(h.transform));
        ASurfaceTransaction_setBufferDataSpace(tx,layer,ADataSpace(h.dataspace));
        ASurfaceTransaction_setBuffer(tx,layer,buffer,acquire); // transfers ownership of acquire FD
        ASurfaceTransaction_setOnComplete(tx,new Completion{shared_from_this(),h.sequence,previous,false,h,dest,vw,vh},onComplete);
        operationAt=nowNs();ASurfaceTransaction_apply(tx);ASurfaceTransaction_delete(tx);operationAt=0;
        {std::lock_guard<std::mutex> l(mu);++received;lastPts=h.pts;}
        std::unique_lock<std::mutex> l(mu);
        bool ok=completed.wait_for(l,std::chrono::seconds(3),[&]{return completedSequence>=h.sequence||stopping.load();});
        if(!ok){l.unlock();stop("Surface transaction completion deadline");return false;}
        return !stopping;
    }
    void sendLoop() {
        while(true){Ack ack{};
            {std::unique_lock<std::mutex> l(ackMu);ackChanged.wait_for(l,std::chrono::milliseconds(200),[&]{return !acks.empty()||senderStopping.load();});if(acks.empty()){if(senderStopping)return;continue;}ack=acks.front();acks.pop_front();}
            uint8_t bytes[24]{};put32(bytes,0x49474131);put32(bytes+4,1);put32(bytes+8,ack.kind);put64(bytes+16,ack.seq);
            iovec io{bytes,sizeof(bytes)};msghdr m{};m.msg_iov=&io;m.msg_iovlen=1;alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
            if(ack.fence>=0){m.msg_control=control;m.msg_controllen=sizeof(control);cmsghdr* c=CMSG_FIRSTHDR(&m);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;c->cmsg_len=CMSG_LEN(sizeof(int));memcpy(CMSG_DATA(c),&ack.fence,sizeof(int));}
            bool sent=waitFd(fd,POLLOUT,500)&&sendmsg(fd,&m,MSG_NOSIGNAL|MSG_DONTWAIT)==sizeof(bytes);
            if(ack.fence>=0)close(ack.fence);
            if(!sent)stop("ACK send failed");
            ackChanged.notify_all();
        }
    }
    void detach() {
        auto* tx=ASurfaceTransaction_create();uint64_t previous=submitted;
        ASurfaceTransaction_setVisibility(tx,layer,ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        ASurfaceTransaction_reparent(tx,layer,nullptr);
        ASurfaceTransaction_setOnComplete(tx,new Completion{shared_from_this(),0,previous,true},onComplete);
        operationAt=nowNs();ASurfaceTransaction_apply(tx);ASurfaceTransaction_delete(tx);operationAt=0;
        std::unique_lock<std::mutex> l(mu);
        if(!completed.wait_for(l,std::chrono::seconds(3),[&]{return clearComplete;}))failure="Detach completion deadline: outstanding items must be abandoned";
    }
    void run() {
        uint64_t previous=0;bool ok=true;
        while(!stopping&&(!deadline||nowNs()<deadline)){
            releaseRetired();
            if(!waitFd(fd,POLLIN,200)){pollfd p{fd,POLLIN,0};if(poll(&p,1,0)>0&&(p.revents&(POLLHUP|POLLERR|POLLNVAL))){stop("Guest disconnected");break;}continue;}
            Packet p;Header h{};
            if(!packet(fd,p,96,false)||!header(p,h,previous)){stop("Invalid IGB1 frame header");ok=false;break;}
            if(!waitFd(fd,POLLIN,1500)||!preflightAHB(fd,h.width,h.height,h.format)){stop("Invalid AHB packet preflight");ok=false;break;}
            AHardwareBuffer* buffer=nullptr;operationAt=nowNs();int imported=AHardwareBuffer_recvHandleFromUnixSocket(fd,&buffer);operationAt=0;
            if(imported!=0||!buffer)fatal("AHB import failed; process exits to reclaim library-owned resources",61);
            AHardwareBuffer_Desc description{};AHardwareBuffer_describe(buffer,&description);
            if(description.width!=h.width||description.height!=h.height||description.layers!=1||description.format!=h.format||description.stride<h.width||(description.usage&AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT)||!(description.usage&AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)){AHardwareBuffer_release(buffer);stop("AHB descriptor mismatch, non-sampleable or protected content");ok=false;break;}
            if(previous==0)__android_log_print(ANDROID_LOG_INFO,TAG,"IRIS_BUFFER_IMPORT id=%d session=%d:%llu socketType=SEQPACKET width=%u height=%u stride=%u format=%u usage=0x%llx guestBufferId=%llu acquireFence=%s",id,pid,(unsigned long long)tick,description.width,description.height,description.stride,description.format,(unsigned long long)description.usage,(unsigned long long)h.bufferId,p.fdCount?"yes":"no");
            int acquire=p.takeFence();
            if(!display(buffer,acquire,h)){if(buffers.find(h.sequence)==buffers.end())AHardwareBuffer_release(buffer);ok=false;break;}
            previous=h.sequence;releaseRetired();
        }
        stop(ok?"Display ended":nullptr);detach();releaseRetired();queue(3,0,-1);
        {std::unique_lock<std::mutex> l(ackMu);ackChanged.wait_for(l,std::chrono::seconds(1),[&]{return acks.empty();});senderStopping=true;}
        ackChanged.notify_all();if(sender.joinable())sender.join();shutdown(fd,SHUT_RDWR);finished=true;
        {std::lock_guard<std::mutex> l(mu);__android_log_print(ANDROID_LOG_INFO,TAG,"IRIS_BUFFER_END id=%d session=%d:%llu received=%llu completed=%llu uniqueLatch=%llu outstanding=%zu error=%s",id,pid,(unsigned long long)tick,(unsigned long long)received,(unsigned long long)presented,(unsigned long long)uniqueLatch,buffers.size(),failure.c_str());}
        // Every fixed connection owns this isolated renderer process. OS exit
        // clears GraphicBuffer caches and any late transaction callback state.
        _exit(ok?0:62);
    }
    std::string stats() {
        std::lock_guard<std::mutex> l(mu);uint64_t now=nowNs();double seconds=(now-lastLog)/1e9;
        if(seconds>=5){
            __android_log_print(ANDROID_LOG_INFO,TAG,"IRIS_BUFFER_STATS {\"id\":%d,\"session\":\"%d:%llu\",\"received\":%llu,\"transactionCompleted\":%llu,\"uniqueLatchTimes\":%llu,\"presentFenceAvailable\":%llu,\"receivedFPS\":%.3f,\"completedFPS\":%.3f,\"sampleWindowSec\":%.3f,\"ptsAgeMs\":%llu}",id,pid,(unsigned long long)tick,(unsigned long long)received,(unsigned long long)presented,(unsigned long long)uniqueLatch,(unsigned long long)withFence,(received-sampledReceived)/seconds,(presented-sampledPresented)/seconds,seconds,(unsigned long long)(lastPts?(now-lastPts)/1000000:0));
            lastLog=now;sampledReceived=received;sampledPresented=presented;
        }
        std::ostringstream out;out<<"实例 "<<id<<" · 缓冲区 "<<received<<" · 事务完成 "<<presented<<" · latch "<<uniqueLatch;
        if(!failure.empty())out<<" · "<<failure;return out.str();
    }
};
std::mutex registryLock;std::map<jlong,std::shared_ptr<State>> registry;jlong nextHandle=1;bool everStarted=false;
std::shared_ptr<State> find(jlong handle){std::lock_guard<std::mutex> l(registryLock);auto it=registry.find(handle);return it==registry.end()?nullptr:it->second;}
}

/* 一进程只接一条固定连接，退出释放GraphicBuffer缓存/迟到回调，不跨实例复用缓存。
 */
extern "C" JNIEXPORT jlong JNICALL Java_com_rootvmos_viewer_DisplayActivity_nativeStart(JNIEnv* env,jclass,jobject surface,jint inputFd,jint id,jint pid,jlong tick,jint seconds,jint width,jint height) {
    if(seconds<0||seconds>900)return 0;
    std::lock_guard<std::mutex> l(registryLock);if(everStarted)fatal("Renderer process cannot reuse GraphicBuffer cache across connections",65);everStarted=true;
    auto s=std::make_shared<State>();s->fd=fcntl(inputFd,F_DUPFD_CLOEXEC,0);if(s->fd<0)return 0;
    int type=0;socklen_t length=sizeof(type);if(getsockopt(s->fd,SOL_SOCKET,SO_TYPE,&type,&length)||type!=SOCK_SEQPACKET)return 0;
    timeval timeout{2,0};setsockopt(s->fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(s->fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    s->window=ANativeWindow_fromSurface(env,surface);if(!s->window)return 0;
    s->layer=ASurfaceControl_createFromWindow(s->window,"IrisGuestBufferViewer");if(!s->layer)return 0;
    // Zero removes the session expiry; the operation watchdog stays active.
    s->id=id;s->pid=pid;s->tick=uint64_t(tick);s->viewWidth=width;s->viewHeight=height;s->lastLog=nowNs();s->deadline=seconds==0 ? 0 : nowNs()+uint64_t(seconds)*1000000000ULL;
    jlong handle=nextHandle++;registry.emplace(handle,s);
    s->sender=std::thread([s]{s->sendLoop();});s->reader=std::thread([s]{s->run();});
    s->watchdog=std::thread([s]{while(!s->finished){uint64_t now=nowNs(),began=s->operationAt;if((began&&now>=began&&now-began>4000000000ULL)||(s->deadline&&now>s->deadline+7000000000ULL))fatal("Receiver hard watchdog expired",63);std::this_thread::sleep_for(std::chrono::milliseconds(100));}});
    return handle;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_rootvmos_viewer_DisplayActivity_nativeStats(JNIEnv* env,jclass,jlong handle){auto s=find(handle);std::string text=s?s->stats():"显示连接已关闭";return env->NewStringUTF(text.c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_rootvmos_viewer_DisplayActivity_nativeResize(JNIEnv*,jclass,jlong handle,jint width,jint height){auto s=find(handle);if(s){s->viewWidth=width;s->viewHeight=height;}}
extern "C" JNIEXPORT void JNICALL Java_com_rootvmos_viewer_DisplayActivity_nativeStop(JNIEnv*,jclass,jlong handle){auto s=find(handle);if(!s)return;s->stop("Host stopped display");if(s->reader.joinable())s->reader.join();if(s->watchdog.joinable())s->watchdog.join();std::lock_guard<std::mutex> l(registryLock);registry.erase(handle);}

// Snapshot of the last COMPLETED transaction, never merely submitted geometry.
// A static frame remains interactive. A resize invalidates input until a frame
// for the new surface size completes; no frame-age heuristic is used.
/* 输出最近完成事务的19项几何给PointerGeometry，静止画面也可交互。
 * View resize后旧几何立即无效，等新尺寸事务完成；字段顺序变动须同步Java。
 */
extern "C" JNIEXPORT jlongArray JNICALL Java_com_rootvmos_viewer_DisplayActivity_nativeGeometry(JNIEnv* env,jclass,jlong handle) {
    jlong values[19]{};auto s=find(handle);
    if(s){std::lock_guard<std::mutex> l(s->mu);const Header& h=s->visibleFrame;const ARect& d=s->visibleDestination;
        values[0]=!s->stopping&&!s->finished&&s->visibleSequence&&s->visibleViewWidth==s->viewWidth&&s->visibleViewHeight==s->viewHeight;
        values[1]=s->visibleSequence;values[2]=d.left;values[3]=d.top;values[4]=d.right;values[5]=d.bottom;
        values[6]=s->visibleViewWidth;values[7]=s->visibleViewHeight;values[8]=h.width;values[9]=h.height;
        values[10]=h.displayWidth;values[11]=h.displayHeight;values[12]=h.crop.left;values[13]=h.crop.top;
        values[14]=h.crop.right;values[15]=h.crop.bottom;values[16]=h.transform;values[17]=s->presented;values[18]=(h.flags>>1)&3;
    }
    jlongArray result=env->NewLongArray(19);if(result)env->SetLongArrayRegion(result,0,19,values);return result;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_rootvmos_viewer_DisplayActivity_nativeInputTypeIsStream(JNIEnv*,jclass,jint fd) {
    int type=0;socklen_t length=sizeof(type);
    return getsockopt(fd,SOL_SOCKET,SO_TYPE,&type,&length)==0&&type==SOCK_STREAM;
}

