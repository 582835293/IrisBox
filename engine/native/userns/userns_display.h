/* Host-facing display preparation and a credential-dropped connector.
 * Never execute Guest code as Host root. Only socket FDs cross the bridge;
 * frame buffers and input keep the existing producer/viewer protocols.
 */
#include <stddef.h>
#include <linux/capability.h>
#include <sys/socket.h>
#include <sys/un.h>

static int private_dir(int parent, const char *name, unsigned base) {
    if (mkdirat(parent, name, 0700) && errno != EEXIST) die("display directory");
    int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) || (st.st_uid != 0 && st.st_uid != base) ||
        fchown(fd, base, base) || fchmod(fd, 0700)) die("display directory identity");
    return fd;
}
static int data_dir(unsigned id, unsigned base) {
    char path[256]; snprintf(path, sizeof(path), HOME_BASE "/instances/%u/data", id);
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) || st.st_uid != base + 1000) die("mapped data directory");
    return fd;
}
static void stage_file(int directory, const char *name, const char *source,
                       const char *contents, unsigned base, mode_t mode) {
    char tmp[96]; snprintf(tmp, sizeof(tmp), ".iris.%d.%s", getpid(), name);
    int fd = openat(directory, tmp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) die("exclusive display staging");
    if (source) {
        int input = open(source, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); struct stat st;
        if (input < 0 || fstat(input, &st) || !S_ISREG(st.st_mode) || st.st_uid) die("trusted display source");
        char buf[16384]; ssize_t n;
        while ((n = read(input, buf, sizeof(buf))) > 0) {
            ssize_t done = 0;
            while (done < n) { ssize_t m = write(fd, buf + done, (size_t)(n - done));
                if (m < 0 && errno == EINTR) continue;
                if (m <= 0) die("stage display write"); done += m; }
        }
        if (n < 0) die("read trusted display source"); close(input);
    } else if (write(fd, contents, strlen(contents)) != (ssize_t)strlen(contents)) die("display token write");
    if (fchown(fd, base, base) || fchmod(fd, mode) || fsync(fd)) die("display staged owner"); close(fd);
    if (renameat(directory, tmp, directory, name)) die("publish display file");
}
static int stage_display(unsigned id, unsigned pid, unsigned long long tick) {
    verify_map(id, pid, tick, 0); unsigned base = profile(id);
    int data = data_dir(id, base); char session[80]; snprintf(session, sizeof(session), "%u_%llu", pid, tick);
    int graphics = private_dir(data, "iris_buffer", base);
    int stream = private_dir(data, "iris_stream", base);
    int gd = private_dir(graphics, session, base), sd = private_dir(stream, session, base);
    /* Re-entry is coordinated by display.sh. unlinkat never follows a hostile
     * symlink or recursively traverses Guest-controlled directory names. */
    const char *stale[] = {"input.sock", "input.ready", "input.identity"};
    for (unsigned i = 0; i < 3; ++i) if (unlinkat(sd, stale[i], 0) && errno != ENOENT) die("retire input entry");
    if (unlinkat(gd, "output.sock", 0) && errno != ENOENT) die("retire graphics entry");
    stage_file(gd, "iris_buffer_producer", HOME_BASE "/display/buffer_bridge/iris_buffer_producer", NULL, base, 0755);
    stage_file(sd, "input.jar", HOME_BASE "/display/stream/input.jar", NULL, base, 0644);
    stage_file(sd, "start_input.sh", HOME_BASE "/display/stream/start_input.sh", NULL, base, 0755);
    stage_file(sd, "stop_input.sh", HOME_BASE "/display/stream/stop_input.sh", NULL, base, 0755);
    unsigned char random[32]; int urandom = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (urandom < 0 || read(urandom, random, sizeof(random)) != sizeof(random)) die("input randomness"); close(urandom);
    char token[65]; for (unsigned i = 0; i < sizeof(random); ++i) snprintf(token + 2*i, 3, "%02x", random[i]);
    stage_file(sd, "input.token", NULL, token, base, 0600);
    close(gd); close(sd); close(graphics); close(stream); close(data);
    verify_map(id, pid, tick, 0); return 0;
}
static int unix_connect(const char *name, int type, int abstract) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX}; size_t len = strlen(name);
    if (len + 1 >= sizeof(addr.sun_path)) { errno = ENAMETOOLONG; die("socket name"); }
    if (abstract) memcpy(addr.sun_path + 1, name, len); else memcpy(addr.sun_path, name, len + 1);
    int fd = socket(AF_UNIX, type | SOCK_CLOEXEC, 0);
    socklen_t size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + len + 1);
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr, size)) die("display socket connect");
    return fd;
}
static void peer_uid(int fd, unsigned uid) {
    struct ucred peer; socklen_t len = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &len) || len != sizeof(peer) || peer.uid != uid)
        { errno = EPERM; die("display socket peer"); }
}
static void socket_line(int fd, char *line, size_t capacity) {
    size_t n = 0;
    for (; n + 1 < capacity; ++n) {
        struct pollfd p = {.fd = fd, .events = POLLIN};
        if (poll(&p, 1, 2000) <= 0 || read(fd, line + n, 1) != 1) die("input handshake timeout");
        if (line[n] == '\n') { line[n] = 0; return; }
        if (line[n] < 32 || line[n] > 126) die("input handshake text");
    }
    errno = E2BIG; die("input handshake length");
}
static int display_bridge(unsigned id, unsigned pid, unsigned long long tick,
                           const char *control_name, unsigned width, unsigned height) {
    verify_map(id, pid, tick, 0); unsigned base = profile(id);
    const size_t prefix = strlen("irisbox_userns_bridge_");
    if (strncmp(control_name, "irisbox_userns_bridge_", prefix) ||
        strspn(control_name + prefix, "0123456789abcdef") != 32 || strlen(control_name) != prefix + 32 ||
        width < 540 || width > 1920 || height < 540 || height > 1920) return 2;
    int control = unix_connect(control_name, SOCK_STREAM, 1); peer_uid(control, 0);
    int data = data_dir(id, base);
    if (fchdir(data)) die("connector data directory"); close(data);
    /* Clear every supplementary/saved identity before touching Guest paths.
     * Kernel BASE is Guest UID0, while still unprivileged against the Host. */
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0) ||
        setgroups(0, NULL) || setresgid(base, base, base) || setresuid(base, base, base) ||
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) die("drop connector Host identity");
    struct __user_cap_header_struct cap_header = { .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0 };
    struct __user_cap_data_struct cap_data[2] = {};
    if (syscall(__NR_capset, &cap_header, cap_data)) die("clear connector capabilities");
    char path[160], session[80], token[128], reply[160], expected[160];
    snprintf(session, sizeof(session), "%u:%llu", pid, tick);
    snprintf(path, sizeof(path), "iris_stream/%u_%llu/input.token", pid, tick);
    if (read_text(path, token, sizeof(token)) != 64 || strspn(token, "0123456789abcdef") != 64) die("private input token");
    snprintf(path, sizeof(path), "iris_buffer/%u_%llu/output.sock", pid, tick);
    int graphics = unix_connect(path, SOCK_SEQPACKET, 0); peer_uid(graphics, base);
    snprintf(path, sizeof(path), "iris_stream/%u_%llu/input.sock", pid, tick);
    int input = unix_connect(path, SOCK_STREAM, 0); peer_uid(input, base);
    char hello[256]; int n = snprintf(hello, sizeof(hello), "HELLO 1 %s %s\n", session, token);
    if (send(input, hello, (size_t)n, MSG_NOSIGNAL) != n) die("input hello");
    memset(token, 0, sizeof(token)); socket_line(input, reply, sizeof(reply));
    snprintf(expected, sizeof(expected), "READY 1 %s %u %u mouse", session, width, height);
    if (strcmp(reply, expected)) die("input geometry handshake");
    int fds[] = {graphics, input}; char marker = 'F';
    struct iovec io = {.iov_base = &marker, .iov_len = 1};
    char ancillary[CMSG_SPACE(sizeof(fds))] = {};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1, .msg_control = ancillary, .msg_controllen = sizeof(ancillary)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(fds)); memcpy(CMSG_DATA(c), fds, sizeof(fds));
    if (sendmsg(control, &msg, MSG_NOSIGNAL) != 1) die("two-FD display handoff");
    close(graphics); close(input); close(control); return 0;
}

/* AudioTrack stays in Host Android. Only a credential-dropped connector may
 * touch the Guest token/socket. The authenticated PCM FD is passed back. */
static int audio_bridge(unsigned id, unsigned pid, unsigned long long tick, const char *name) {
    verify_map(id, pid, tick, 0); unsigned base = profile(id);
    const size_t prefix = strlen("irisbox_audio_bridge_");
    if (strncmp(name, "irisbox_audio_bridge_", prefix) || strlen(name) != prefix + 32 ||
        strspn(name + prefix, "0123456789abcdef") != 32) return 2;
    int control = unix_connect(name, SOCK_STREAM, 1); peer_uid(control, 0);
    int data = data_dir(id, base);
    if (fchdir(data)) die("audio data directory"); close(data);
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0) ||
        setgroups(0, NULL) || setresgid(base, base, base) || setresuid(base, base, base) ||
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) die("drop audio connector identity");
    struct __user_cap_header_struct header = {.version = _LINUX_CAPABILITY_VERSION_3, .pid = 0};
    struct __user_cap_data_struct caps[2] = {};
    if (syscall(__NR_capset, &header, caps)) die("clear audio connector capabilities");
    char token[128];
    ssize_t n = read_text("irisbox_audio/token", token, sizeof(token));
    if (n == 65 && token[64] == '\n') token[--n] = 0;
    if (n != 64 || strspn(token, "0123456789abcdef") != 64) die("audio token format");
    int pcm = unix_connect("irisbox_audio/pcm.sock", SOCK_STREAM, 0); peer_uid(pcm, base);
    unsigned char hello[66] = {0, 64}; memcpy(hello + 2, token, 64); memset(token, 0, sizeof(token));
    if (send(pcm, hello, sizeof(hello), MSG_NOSIGNAL) != sizeof(hello)) die("audio token handshake");
    char marker = 'A', ancillary[CMSG_SPACE(sizeof(pcm))] = {};
    struct iovec io = {.iov_base = &marker, .iov_len = 1};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1, .msg_control = ancillary, .msg_controllen = sizeof(ancillary)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(pcm)); memcpy(CMSG_DATA(c), &pcm, sizeof(pcm));
    if (sendmsg(control, &msg, MSG_NOSIGNAL) != 1) die("audio FD handoff");
    close(pcm); close(control); return 0;
}
