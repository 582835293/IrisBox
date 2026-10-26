/* Iris shifted-root launcher. Host prepares; mapped Guest runs Android init.
 * The Host supervisor never joins the Guest-delegated cgroups. A Guest cannot
 * obtain Host UID/GID 0 through its maps. Shared-kernel/device risk remains.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/mount.h>
#include <linux/nsfs.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define HOME_BASE "/data/irisbox/runtime"
#define MAPS HOME_BASE "/config/userns_v1"
#define SPAN 100000U
#define FIRST_BASE 2000000000U /* Disjoint from previous Iris ranges [1100000000,2000000000). */
#define SLOTS 128U
static __attribute__((noreturn)) void die(const char *s) { perror(s); exit(3); }
static unsigned number(const char *s) {
    char *end; errno = 0; unsigned long n = strtoul(s, &end, 10);
    if (!*s || *s < '0' || *s > '9' || *end || errno || n > 2147483647UL) {
        errno = EINVAL; die("integer");
    }
    return (unsigned)n;
}
static ssize_t read_text(const char *path, char *s, size_t capacity) {
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); if (fd < 0) return -1;
    ssize_t n = read(fd, s, capacity - 1); int error = errno; close(fd); errno = error;
    if (n < 0) return -1;
    s[n] = 0; if ((size_t)n == capacity - 1) { errno = E2BIG; return -1; }
    return n;
}
static void put(const char *path, const char *s) {
    int fd = open(path, O_WRONLY | O_CLOEXEC | O_NOFOLLOW); if (fd < 0) die(path);
    size_t left = strlen(s); const char *p = s;
    while (left) { ssize_t n = write(fd, p, left); if (n < 0 && errno == EINTR) continue;
        if (n <= 0) die("write control"); p += n; left -= (size_t)n; }
    close(fd);
}
static void record(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0 || write(fd, value, strlen(value)) != (ssize_t)strlen(value) || fsync(fd)) die("Host record");
    close(fd);
}
static void host_only(void) {
    char map[256]; unsigned long long a, b, n; char c;
    if (getuid() || geteuid() || read_text("/proc/self/uid_map", map, sizeof(map)) < 0 ||
        sscanf(map, "%llu %llu %llu %c", &a, &b, &n, &c) != 3 || a || b || n != 4294967295ULL) {
        errno = EPERM; die("initial Host root required");
    }
}
static void revision(unsigned id, char *out) {
    char path[256], data[128];
    snprintf(path, sizeof(path), HOME_BASE "/config/instances_v1/%u.record", id);
    if (read_text(path, data, sizeof(data)) < 0) die("registered instance required");
    data[strcspn(data, "\r\n")] = 0;
    if (strlen(data) != 64 || strspn(data, "0123456789abcdef") != 64) die("data revision");
    strcpy(out, data);
}
static unsigned profile(unsigned id) {
    char path[256], data[256], rev[65], saved[65], extra;
    unsigned version, base, span;
    revision(id, rev); snprintf(path, sizeof(path), MAPS "/%u.map", id);
    struct stat st;
    if (lstat(path, &st) || !S_ISREG(st.st_mode) || st.st_uid || (st.st_mode & 077) ||
        read_text(path, data, sizeof(data)) < 0 ||
        sscanf(data, "%u %u %u %64s %c", &version, &base, &span, saved, &extra) != 4 ||
        version != 1 || span != SPAN || base < FIRST_BASE || (base - FIRST_BASE) % SPAN ||
        (base - FIRST_BASE) / SPAN >= SLOTS || strcmp(rev, saved)) {
        errno = EINVAL; die("validated User namespace profile required");
    }
    return base;
}
static unsigned enable(unsigned id) {
    char rev[65], path[256], data[256], value[256]; revision(id, rev);
    snprintf(path, sizeof(path), "/sys/fs/cgroup/irisbox_%u/cgroup.events", id);
    if (read_text(path, data, sizeof(data)) >= 0 && !strstr(data, "populated 0\n")) {
        errno = EBUSY; die("stop instance before mapping");
    }
    if (mkdir(MAPS, 0700) && errno != EEXIST) die("mapping directory");
    int dir = open(MAPS, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); struct stat st;
    if (dir < 0 || fstat(dir, &st) || st.st_uid || (st.st_mode & 077)) die("mapping directory owner");
    close(dir);
    int lock = open(MAPS "/ranges.lock", O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX)) die("mapping allocator lock");
    unsigned char used[SLOTS] = {}; unsigned chosen = SLOTS;
    DIR *d = opendir(MAPS); if (!d) die("mapping registry"); struct dirent *e;
    snprintf(path, sizeof(path), "%u.map", id);
    while ((e = readdir(d))) {
        size_t len = strlen(e->d_name); if (len < 5 || strcmp(e->d_name + len - 4, ".map")) continue;
        char source[512]; snprintf(source, sizeof(source), MAPS "/%s", e->d_name);
        unsigned v, b, s; char r[65], x;
        if (lstat(source, &st) || !S_ISREG(st.st_mode) || st.st_uid || (st.st_mode & 077) ||
            read_text(source, data, sizeof(data)) < 0 || sscanf(data, "%u %u %u %64s %c", &v, &b, &s, r, &x) != 4 ||
            v != 1 || s != SPAN || b < FIRST_BASE || (b - FIRST_BASE) % SPAN || (b - FIRST_BASE) / SPAN >= SLOTS)
            die("invalid existing map record");
        unsigned slot = (b - FIRST_BASE) / SPAN;
        if (used[slot]) die("overlapping User namespace ranges"); used[slot] = 1;
        if (!strcmp(e->d_name, path)) chosen = slot;
    }
    closedir(d);
    if (chosen == SLOTS) for (unsigned i = 0; i < SLOTS; ++i) if (!used[i]) { chosen = i; break; }
    if (chosen == SLOTS) { errno = ENOSPC; die("User namespace ID space"); }
    unsigned base = FIRST_BASE + chosen * SPAN;
    char tmp[256]; snprintf(tmp, sizeof(tmp), MAPS "/.%u.%d.tmp", id, getpid());
    snprintf(value, sizeof(value), "1 %u %u %s\n", base, SPAN, rev); record(tmp, value);
    snprintf(path, sizeof(path), MAPS "/%u.map", id); if (rename(tmp, path)) die("commit mapping");
    dir = open(MAPS, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0 || fsync(dir)) die("persist mapping directory"); close(dir);
    close(lock); return base;
}
static unsigned long long ticks(unsigned pid) {
    char path[64], text[4096]; snprintf(path, sizeof(path), "/proc/%u/stat", pid);
    if (read_text(path, text, sizeof(text)) < 0) return 0;
    char *p = strrchr(text, ')'), *save = NULL; if (!p) return 0;
    p = strtok_r(p + 2, " ", &save); if (!p || *p == 'Z') return 0;
    for (int i = 3; p && i < 22; ++i) p = strtok_r(NULL, " ", &save);
    return p ? strtoull(p, NULL, 10) : 0;
}
static int map_is(unsigned pid, unsigned base, const char *kind) {
    char path[128], text[256], extra; unsigned a, b, n;
    snprintf(path, sizeof(path), "/proc/%u/%s_map", pid, kind);
    return read_text(path, text, sizeof(text)) > 0 &&
        sscanf(text, "%u %u %u %c", &a, &b, &n, &extra) == 3 && !a && b == base && n == SPAN;
}
static void verify_map(unsigned id, unsigned pid, unsigned long long tick, int preinit) {
    unsigned base = profile(id);
    if (!tick || ticks(pid) != tick || !map_is(pid, base, "uid") || !map_is(pid, base, "gid") ||
        !map_is(pid, base, "projid")) die("Guest mapped identity");
    if (!preinit) {
        char status_path[128], status[16384];
        snprintf(status_path, sizeof(status_path), "/proc/%u/status", pid);
        if (read_text(status_path, status, sizeof(status)) < 0) die("Guest init credentials");
        const char *tags[] = {"\nUid:", "\nGid:"};
        for (unsigned i = 0; i < 2; ++i) {
            char *line = strstr(status, tags[i]); unsigned real, effective, saved, fs;
            if (!line || sscanf(line + 5, "%u %u %u %u", &real, &effective, &saved, &fs) != 4 ||
                real != base || effective != base || saved != base || fs != base) {
                errno = EPERM; die("Guest init must use mapped credentials");
            }
        }
    }
    char path[128]; snprintf(path, sizeof(path), "/proc/%u/ns/user", pid);
    int u = open(path, O_RDONLY | O_CLOEXEC); struct stat us, host;
    if (u < 0 || fstat(u, &us) || stat("/proc/self/ns/user", &host) || us.st_ino == host.st_ino) die("distinct Guest User namespace");
    const char *names[] = {"pid", "net", "ipc", "uts", "mnt", "cgroup"};
    for (unsigned i = 0; i < 6; ++i) {
        if (preinit && i >= 4) continue; /* prepared Host mounts, not yet released */
        snprintf(path, sizeof(path), "/proc/%u/ns/%s", pid, names[i]);
        int ns = open(path, O_RDONLY | O_CLOEXEC), owner = ns < 0 ? -1 : ioctl(ns, NS_GET_USERNS);
        struct stat os;
        if (owner < 0 || fstat(owner, &os) || os.st_ino != us.st_ino) die("namespace belongs to Guest User namespace");
        close(owner); close(ns);
    }
    close(u);
    if (ticks(pid) != tick) { errno = ESTALE; die("Guest identity changed during verify"); }
}
#include "userns_fs.h"

static void join_child(unsigned id, pid_t pid) {
    const char *controllers[] = {"/dev/memcg", "/sys/fs/cgroup", "/dev/cpuctl", "/dev/cpuset", "/dev/blkio"};
    char path[256], value[32]; snprintf(value, sizeof(value), "%d\n", pid);
    for (unsigned i = 0; i < 5; ++i) {
        snprintf(path, sizeof(path), "%s/irisbox_%u/managed/cgroup.procs", controllers[i], id); put(path, value);
    }
}
static void seal_and_map(unsigned id, unsigned base) {
    char root[256], path[512]; snprintf(root, sizeof(root), HOME_BASE "/instances/%u/rootfs", id);
    const char *trees[] = {"", "/vendor", "/dev", "/apex", "/linkerconfig", "/mnt", "/data", "/metadata", "/sbin"};
    for (unsigned i = 0; i < sizeof(trees)/sizeof(trees[0]); ++i) {
        snprintf(path, sizeof(path), "%s%s", root, trees[i]); struct stat st;
        if (lstat(path, &st) && i == 8 && errno == ENOENT) continue;
        shift_tree(path, base);
    }
    /* Delegate only managed subtrees. The outer limit and Host supervisor
     * remain Host-root-owned/outside; Guest cgroup.kill cannot kill the broker.
     */
    const char *controllers[] = {"/dev/memcg", "/sys/fs/cgroup", "/dev/cpuctl", "/dev/cpuset", "/dev/blkio"};
    for (unsigned i = 0; i < 5; ++i) {
        snprintf(path, sizeof(path), "%s/irisbox_%u/managed", controllers[i], id); shift_tree(path, base);
    }
    struct mount_attr readonly = {.attr_set = MOUNT_ATTR_RDONLY | MOUNT_ATTR_NOSUID};
    if (syscall(__NR_mount_setattr, AT_FDCWD, root, 0, &readonly, sizeof(readonly))) die("seal system root");
    snprintf(path, sizeof(path), "%s/vendor", root);
    if (syscall(__NR_mount_setattr, AT_FDCWD, path, 0, &readonly, sizeof(readonly))) die("seal vendor root");
    printf("USERNS_FILES_PREPARED base=%u shifted=%lu system_readonly=yes\n", base, shifted_files);
}
struct launch_args {int go[2], parent_fd; unsigned id, base; char root[256];};
struct release {unsigned pid; unsigned long long tick; unsigned gles;};
static int guest_main(void *arg) {
    struct launch_args *a = arg; close(a->go[1]);
    if (prctl(PR_SET_PDEATHSIG, SIGKILL)) _exit(3);
    struct release release;
    if (read(a->go[0], &release, sizeof(release)) != sizeof(release)) _exit(3);
    close(a->go[0]);
    if (setgroups(0, NULL) || setresgid(0, 0, 0) || setresuid(0, 0, 0)) _exit(3);
    struct pollfd parent = {a->parent_fd, POLLIN, 0};
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) || poll(&parent, 1, 0) != 0) _exit(3);
    close(a->parent_fd);
    if (unshare(CLONE_NEWNS | CLONE_NEWCGROUP) || mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL)) _exit(3);
    char net[128], netpath[64]; snprintf(netpath, sizeof(netpath), "/proc/%u/ns/net", release.pid);
    ssize_t n = readlink(netpath, net, sizeof(net)-1); if (n < 0) _exit(3); net[n] = 0;
    /* Bind before chdir: an existing cwd keeps pointing at the covered,
     * inherited/locked mount and pivot_root would reject it with EINVAL.
     */
    if (mount("/mnt", "/mnt", NULL, MS_BIND | MS_REC, NULL)) { perror("Guest root bind"); _exit(3); }
    if (chdir("/mnt")) { perror("Guest root cwd"); _exit(3); }
    if (syscall(SYS_pivot_root, ".", "old_root")) { perror("Guest pivot_root"); _exit(3); }
    if (chdir("/") || umount2("/old_root", MNT_DETACH)) { perror("Guest detach old root"); _exit(3); }
    int null = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (null < 0 || dup2(null, 0) < 0) _exit(3); close(null);
    if (syscall(__NR_close_range, 3U, ~0U, 0)) _exit(3);
    char id[24], session[64]; snprintf(id, sizeof(id), "%u", a->id);
    snprintf(session, sizeof(session), "%u:%llu", release.pid, release.tick);
    setenv("IRIS_GUEST_INIT", "1", 1); unsetenv("IRIS_GUEST_INIT_PROBE");
    setenv("IRIS_NETD_NO_BPF", "1", 1); setenv("IRIS_GUEST_NO_BPF", "1", 1);
    setenv("PATH", "/system/bin:/system/xbin:/vendor/bin:/apex/com.android.art/bin", 1);
    setenv("ANDROID_ROOT", "/system", 1); setenv("ANDROID_DATA", "/data", 1);
    if (release.gles) setenv("MESA_GLES_VERSION_OVERRIDE", "3.0", 1);
    else unsetenv("MESA_GLES_VERSION_OVERRIDE");
    /* APEX is not active yet; mksh's ELF interpreter is in /apex. The
     * existing static BusyBox can run this pre-init step without Host APEX.
     */
    execl("/system/bin/busybox", "busybox", "sh", "/system/bin/irisbox_userns_entry.sh", id, session, net, NULL);
    perror("Guest entry"); _exit(3);
}
static int wait_result(pid_t pid) {
    int status; while (waitpid(pid, &status, 0) < 0) if (errno != EINTR) die("wait child");
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
static void guest_proc_mount(unsigned pid, const char *root, unsigned base) {
    char path[512]; snprintf(path, sizeof(path), "/proc/%u/ns/net", pid);
    int net = open(path, O_RDONLY | O_CLOEXEC); if (net < 0 || setns(net, CLONE_NEWNET)) die("Guest proc network view"); close(net);
    snprintf(path, sizeof(path), "/proc/%u/ns/pid", pid);
    int ns = open(path, O_RDONLY | O_CLOEXEC); if (ns < 0 || setns(ns, CLONE_NEWPID)) die("select Guest proc PID view");
    close(ns); pid_t child = fork(); if (child < 0) die("proc preparer fork");
    if (!child) {
        snprintf(path, sizeof(path), "%s/proc", root);
        if (mount("proc", path, "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL)) _exit(3);
        char sys[512]; snprintf(sys, sizeof(sys), "%s/sys", path);
        if (mount(sys, sys, NULL, MS_BIND, NULL) || mount(NULL, sys, NULL, MS_REMOUNT | MS_BIND | MS_RDONLY, NULL)) _exit(3);
        const char *families[] = {"ipv4", "ipv6"};
        for (unsigned i = 0; i < 2; ++i) {
            char conf[768]; snprintf(conf, sizeof(conf), "%s/net/%s/conf/eth0", sys, families[i]);
            if (mount(conf, conf, NULL, MS_BIND, NULL) || mount(NULL, conf, NULL, MS_REMOUNT | MS_BIND, NULL)) _exit(3);
        }
        /* Android's /proc/cmdline is root-only in this build. Supply only the
         * real fixed platform selector, not Host serials or boot credentials.
         */
        char boot[512]; snprintf(boot, sizeof(boot), "%s/dev/.iris_platform_cmdline", root);
        record(boot, "androidboot.hardware=rpi4\n");
        if (chown(boot, base, base) || chmod(boot, 0444)) _exit(3);
        char cmdline[512]; snprintf(cmdline, sizeof(cmdline), "%s/proc/cmdline", root);
        if (mount(boot, cmdline, NULL, MS_BIND, NULL) ||
            mount(NULL, cmdline, NULL, MS_REMOUNT | MS_BIND | MS_RDONLY, NULL)) _exit(3);
        _exit(0);
    }
    if (wait_result(child)) die("private Guest proc");
}
static int launch(unsigned id, const char *log) {
    struct launch_args a = {.id = id, .base = profile(id)};
    snprintf(a.root, sizeof(a.root), HOME_BASE "/instances/%u/rootfs", id);
    char prefix[256]; snprintf(prefix, sizeof(prefix), HOME_BASE "/instances/%u/", id);
    if (strncmp(log, prefix, strlen(prefix)) || !log[strlen(prefix)] ||
        strspn(log + strlen(prefix), "0123456789_") != strlen(log + strlen(prefix))) die("attempt path");
    if (unshare(CLONE_NEWNS) || mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL)) die("Host preparer private mounts");
    /* Inherit ceilings before losing initial-userns CAP_SYS_RESOURCE.
     * No realtime scheduling: an untrusted Guest must not starve the Host.
     * CFS nice is bounded by the outer CPU cgroup; pinned pages by memory.
     */
    struct rlimit nice_limit = {40, 40}, files = {32768, 32768};
    struct rlimit locked = {8*1024*1024, 8*1024*1024}, realtime = {0, 0};
    if (setrlimit(RLIMIT_NICE, &nice_limit) || setrlimit(RLIMIT_NOFILE, &files) ||
        setrlimit(RLIMIT_MEMLOCK, &locked) || setrlimit(RLIMIT_RTPRIO, &realtime)) die("Guest inherited rlimits");
    if (pipe2(a.go, O_CLOEXEC)) die("release pipe");
    a.parent_fd = (int)syscall(__NR_pidfd_open, getpid(), 0); if (a.parent_fd < 0) die("supervisor pidfd");
    void *stack = malloc(1024*1024); if (!stack) die("clone stack");
    pid_t guest = clone(guest_main, (char *)stack + 1024*1024, SIGCHLD | CLONE_NEWUSER | CLONE_NEWPID |
        CLONE_NEWNET | CLONE_NEWIPC | CLONE_NEWUTS | CLONE_NEWCGROUP, &a);
    if (guest < 0) die("clone shifted Guest");
    close(a.go[0]); close(a.parent_fd);
    char path[512], value[256];
    snprintf(value, sizeof(value), "0 %u %u\n", a.base, SPAN);
    snprintf(path, sizeof(path), "/proc/%d/uid_map", guest); put(path, value);
    snprintf(path, sizeof(path), "/proc/%d/gid_map", guest); put(path, value);
    /* Keep project-ID bookkeeping disjoint, alongside UID/GID mappings.
     * The matched kernel still forbids changing project quota state from a
     * noninitial user namespace; the Guest vold profile handles that limit.
     * This map alone does not enable filesystem quota enforcement.
     */
    snprintf(path, sizeof(path), "/proc/%d/projid_map", guest); put(path, value);
    join_child(id, guest);
    struct release go = {.pid = (unsigned)guest, .tick = ticks((unsigned)guest)};
    snprintf(path, sizeof(path), "%s/userns.launch.identity", log);
    snprintf(value, sizeof(value), "%d %llu %u\n", guest, go.tick, a.base); record(path, value);
    printf("USERNS_LAUNCH id=%u base=%u Host_pid=%d supervisor=%d\n", id, a.base, guest, getpid());
    pid_t prep = fork(); if (prep < 0) die("Host preparation fork");
    if (!prep) {
        const char *ns[] = {"net", "ipc", "uts", "cgroup"};
        int flags[] = {CLONE_NEWNET, CLONE_NEWIPC, CLONE_NEWUTS, CLONE_NEWCGROUP};
        for (unsigned i = 0; i < 4; ++i) {
            snprintf(path, sizeof(path), "/proc/%d/ns/%s", guest, ns[i]);
            int fd = open(path, O_RDONLY | O_CLOEXEC); if (fd < 0 || setns(fd, flags[i])) _exit(3); close(fd);
        }
        char number[32], gid[32]; snprintf(number, sizeof(number), "%u", id); snprintf(gid, sizeof(gid), "%d", guest);
        setenv("IRIS_USERNS_PREPARE_PID", gid, 1); setenv("IRIS_RUNTIME_LOG", log, 1);
        execl("/data/irisbox/host/bin/busybox", "busybox", "timeout", "-s", "KILL", "70", "/system/bin/sh",
            HOME_BASE "/inner.sh", number, NULL); _exit(3);
    }
    int result = wait_result(prep);
    if (result) { kill(guest, SIGKILL); wait_result(guest); free(stack); return result; }
    snprintf(path, sizeof(path), HOME_BASE "/instances/%u/graphics.effective", id);
    if (read_text(path, value, sizeof(value)) < 0) die("effective graphics profile");
    go.gles = !strcmp(value, "opengl\n");
    seal_and_map(id, a.base);
    pid_t proc = fork(); if (proc < 0) die("proc setup fork");
    if (!proc) { guest_proc_mount((unsigned)guest, a.root, a.base); _exit(0); }
    if (wait_result(proc)) { kill(guest, SIGKILL); wait_result(guest); return 3; }
    /* /data/irisbox remains Host-root-only (0700). Expose the prepared root
     * at /mnt ONLY in this already-private preparer/child mount namespace.
     * No Host-visible directory is created or chmodded; /mnt inside Guest
     * after pivot is still its own tmpfs, not this pre-pivot alias. */
    if (mount(a.root, "/mnt", NULL, MS_BIND | MS_REC, NULL)) die("private mapped root alias");
    snprintf(value, sizeof(value), "%d %llu\n", guest, go.tick);
    snprintf(path, sizeof(path), HOME_BASE "/instances/%u/init.identity", id); record(path, value);
    snprintf(path, sizeof(path), "%s/init.identity", log); record(path, value);
    if (write(a.go[1], &go, sizeof(go)) != sizeof(go)) die("release mapped Guest"); close(a.go[1]);
    result = wait_result(guest); free(stack);
    printf("USERNS_GUEST_EXIT id=%u status=%d\n", id, result); return result;
}
#include "userns_display.h"
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0); umask(022); host_only();
    if (argc < 3) return 2;
    unsigned id = number(argv[2]); if (id < 1007 || id > 1134) return 2;
    if (!strcmp(argv[1], "enable") && argc == 3) { printf("%u\n", enable(id)); return 0; }
    if (!strcmp(argv[1], "profile") && argc == 3) { printf("%u\n", profile(id)); return 0; }
    if (!strcmp(argv[1], "launch") && argc == 4) return launch(id, argv[3]);
    if (!strcmp(argv[1], "stage-display") && argc == 5)
        return stage_display(id, number(argv[3]), strtoull(argv[4], NULL, 10));
    if (!strcmp(argv[1], "bridge") && argc == 8)
        return display_bridge(id, number(argv[3]), strtoull(argv[4], NULL, 10), argv[5], number(argv[6]), number(argv[7]));
    if (!strcmp(argv[1], "audio-bridge") && argc == 6)
        return audio_bridge(id, number(argv[3]), strtoull(argv[4], NULL, 10), argv[5]);
    if ((!strcmp(argv[1], "verify") || !strcmp(argv[1], "verify-preinit")) && argc == 5) {
        verify_map(id, number(argv[3]), strtoull(argv[4], NULL, 10), !strcmp(argv[1], "verify-preinit")); return 0;
    }
    fputs("Use enable/profile/launch/verify/verify-preinit ID ...\n", stderr); return 2;
}
