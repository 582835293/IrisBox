/* AOSP/bionic static helper: cgroup namespace and complete Guest entry.
 * No Android service is started here. The regular Guest init owns services.
 */
/* 静态namespace助手：wrapper建cgroup空间，ctl exec进入已运行Guest。
 * 这里只准备/进入环境；传Host PID:start tick，不传显示序号，不管理Android服务。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void die(const char *message) { perror(message); exit(3); }

static int read_proc(long pid, const char *file, char *buf, size_t capacity) {
    char path[128];
    snprintf(path, sizeof(path), "/proc/%ld/%s", pid, file);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, capacity - 1);
    close(fd);
    if (n < 0) return -1;
    buf[n] = 0;
    return (int)n;
}

/* comm可含空格，先找stat最后右括号再解析第22字段，不能按全行空格切。
 * 核嵌套NSpid末项1及iris_init；ctl侧还有overlay/cgroup等完整检查。
 */
static int verify(long pid, unsigned long long tick) {
    char buf[8192];
    if (pid <= 1 || read_proc(pid, "stat", buf, sizeof(buf)) < 0) return 0;
    char *p = strrchr(buf, ')');
    if (!p) return 0;
    char *save = NULL;
    p = strtok_r(p + 2, " ", &save);
    for (int field = 3; p && field < 22; ++field) p = strtok_r(NULL, " ", &save);
    if (!p || strtoull(p, NULL, 10) != tick) return 0;
    if (read_proc(pid, "status", buf, sizeof(buf)) < 0) return 0;
    p = strstr(buf, "NSpid:");
    if (!p) return 0;
    char *end = strchr(p, '\n');
    if (end) *end = 0;
    long last = -1;
    int count = 0;
    p += 6;
    while (*p) {
        while (*p == ' ' || *p == '\t') ++p;
        if (!*p) break;
        last = strtol(p, &end, 10);
        if (end == p) return 0;
        p = end;
        ++count;
    }
    if (count < 2 || last != 1) return 0;
    int n = read_proc(pid, "cmdline", buf, sizeof(buf));
    if (n <= 0 || !strstr(buf, "/system/bin/iris_init")) return 0;
    return 1;
}

int main(int argc, char **argv) {
    if (argc >= 4 && !strcmp(argv[1], "new-cgroup") && !strcmp(argv[2], "--")) {
        if (unshare(CLONE_NEWCGROUP)) die("unshare cgroup");
        execvp(argv[3], argv + 3);
        die("exec new-cgroup command");
    }
    if (argc < 6 || strcmp(argv[1], "enter") || strcmp(argv[4], "--")) {
        fprintf(stderr, "usage: iris_runtime_ns new-cgroup -- command...\n"
                        "       iris_runtime_ns enter HOST_PID START_TICK -- command...\n");
        return 2;
    }
    long pid = strtol(argv[2], NULL, 10);
    unsigned long long tick = strtoull(argv[3], NULL, 10);
    if (!verify(pid, tick)) { fprintf(stderr, "Guest identity rejected\n"); return 3; }
    /* Open all target handles as Host root, recheck the session, then enter
     * User FIRST. Its capabilities govern the target's other namespaces.
     * No Host control/directory descriptor survives the Guest exec.
     */
    const char *names[] = {"cgroup", "ipc", "uts", "net", "pid", "mnt"};
    int flags[] = {CLONE_NEWCGROUP, CLONE_NEWIPC, CLONE_NEWUTS, CLONE_NEWNET, CLONE_NEWPID, CLONE_NEWNS};
    int fds[6];
    char path[128];
    snprintf(path, sizeof(path), "/proc/%ld/ns/user", pid);
    int user = open(path, O_RDONLY | O_CLOEXEC); struct stat us, self;
    if (user < 0 || fstat(user, &us) || stat("/proc/self/ns/user", &self)) die("Guest User namespace");
    int mapped = us.st_ino != self.st_ino;
    if (!mapped) die("unmapped Guest entry refused");
    if (mapped) {
        char text[256], extra; unsigned a, base, span, ga, gb, gn;
        if (read_proc(pid, "uid_map", text, sizeof(text)) < 0 ||
            sscanf(text, "%u %u %u %c", &a, &base, &span, &extra) != 3 || a || span != 100000 ||
            base < 2000000000U || base >= 2012800000U || base % 100000) die("Guest UID map");
        if (read_proc(pid, "gid_map", text, sizeof(text)) < 0 ||
            sscanf(text, "%u %u %u %c", &ga, &gb, &gn, &extra) != 3 || ga || gb != base || gn != span)
            die("Guest GID map");
    }
    for (int i = 0; i < 6; ++i) {
        snprintf(path, sizeof(path), "/proc/%ld/ns/%s", pid, names[i]);
        fds[i] = open(path, O_RDONLY | O_CLOEXEC);
        if (fds[i] < 0) die(path);
    }
    snprintf(path, sizeof(path), "/proc/%ld/root", pid);
    int root = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) die("Guest root");
    if (!verify(pid, tick)) return 3;
    if (mapped && (setns(user, CLONE_NEWUSER) || setgroups(0, NULL) ||
                   setresgid(0, 0, 0) || setresuid(0, 0, 0))) die("enter mapped Root");
    close(user);
    for (int i = 0; i < 6; ++i) {
        if (setns(fds[i], flags[i])) die(names[i]);
        close(fds[i]);
    }
    /* PID空间setns影响随后子进程，所以fork后在子进程chroot/exec，父进程转交退出状态。
     */
    pid_t child = fork();
    if (child < 0) die("fork Guest diagnostic");
    if (!child) {
        if (fchdir(root) || chroot(".") || chdir("/")) die("Guest chroot");
        close(root);
        if (syscall(__NR_close_range, 3U, ~0U, 0)) die("close Host descriptors");
        umask(022);
        setenv("PATH", "/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.art/bin", 1);
        setenv("ANDROID_ROOT", "/system", 1);
        setenv("ANDROID_DATA", "/data", 1);
        execvp(argv[5], argv + 5);
        die("exec Guest diagnostic");
    }
    close(root);
    int status;
    while (waitpid(child, &status, 0) < 0) if (errno != EINTR) die("wait Guest diagnostic");
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
