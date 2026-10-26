/* Host权限控制助手：network登记/移除，watchdog调用ensure，不是Google专用服务。
 * 原Host表保持；outer按netns cookie选择Guest私有表，权限事实由原厂PermissionMonitor维护。
 * 限定Android13/Linux5.15单程序socket挂载，未知对象拒绝覆盖。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/bpf.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include "policy_insns.h"

#ifndef SO_NETNS_COOKIE
#define SO_NETNS_COOKIE 71
#endif
#define BASE "/data/irisbox/runtime"
#define PINS "/sys/fs/bpf/iris_inet_v1"
#define HOST_MAP "/sys/fs/bpf/netd_shared/map_netd_uid_permission_map"
#define STOCK_PROG "/sys/fs/bpf/netd_shared/prog_netd_cgroupsock_inet_create"
#define OUTER PINS "/domains"
#define PROGRAM PINS "/program"
#define ORIGINAL PINS "/original"
static int root_fd, host_fd, outer_fd, program_fd, original_fd;
static struct bpf_map_info host_info;

static __attribute__((noreturn)) void fail(const char *what) { perror(what); exit(3); }
static int bpf(int command, union bpf_attr *attr) {
    return (int)syscall(__NR_bpf, command, attr, sizeof(*attr));
}
static uint64_t number(const char *s) {
    char *end;
    errno = 0;
    if (!s[0] || s[0] == '-' || s[0] == '+') { errno = EINVAL; fail("number"); }
    unsigned long long n = strtoull(s, &end, 10);
    if (*end || errno) { errno = EINVAL; fail("number"); }
    return n;
}
static int obj_get(const char *path) {
    union bpf_attr a = {.pathname = (uint64_t)(uintptr_t)path};
    return bpf(BPF_OBJ_GET, &a);
}
static void pin(int fd, const char *path) {
    union bpf_attr a = {.pathname = (uint64_t)(uintptr_t)path, .bpf_fd = (uint32_t)fd};
    if (bpf(BPF_OBJ_PIN, &a)) fail("pin");
}
static void info(int fd, void *value, size_t len) {
    union bpf_attr a = {};
    a.info.bpf_fd = (uint32_t)fd;
    a.info.info_len = (uint32_t)len;
    a.info.info = (uint64_t)(uintptr_t)value;
    if (bpf(BPF_OBJ_GET_INFO_BY_FD, &a)) fail("object info");
}
static uint32_t prog_id(int fd) {
    struct bpf_prog_info i = {};
    info(fd, &i, sizeof(i));
    if (i.type != BPF_PROG_TYPE_CGROUP_SOCK) { errno = EINVAL; fail("program type"); }
    return i.id;
}
static uint32_t map_id(int fd) {
    struct bpf_map_info i = {};
    info(fd, &i, sizeof(i));
    return i.id;
}
static uint32_t attached(void) {
    uint32_t ids[4] = {};
    union bpf_attr a = {};
    a.query.target_fd = (uint32_t)root_fd;
    a.query.attach_type = BPF_CGROUP_INET_SOCK_CREATE;
    a.query.prog_cnt = 4;
    a.query.prog_ids = (uint64_t)(uintptr_t)ids;
    if (bpf(BPF_PROG_QUERY, &a)) fail("query root policy");
    // Match this Android 13 kernel's legacy single-program attachment exactly.
    if (a.query.prog_cnt != 1 || a.query.attach_flags != 0) {
        errno = EBUSY; fail("unexpected root socket policy");
    }
    return ids[0];
}
/* 原子替换flags=0的单一socket-create挂载，不先detach，不拆其他BPF hooks。
 */
static void attach(int fd) {
    union bpf_attr a = {};
    a.target_fd = (uint32_t)root_fd;
    a.attach_bpf_fd = (uint32_t)fd;
    a.attach_type = BPF_CGROUP_INET_SOCK_CREATE;
    // flags=0 atomically replaces the existing legacy single program. Do NOT
    // detach first, and do NOT change ALLOW_MULTI/ALLOW_OVERRIDE on the Host.
    if (bpf(BPF_PROG_ATTACH, &a)) fail("atomic socket policy attach");
    if (attached() != prog_id(fd)) { errno = EBUSY; fail("attachment changed concurrently"); }
}
static int create_uid_map(void) {
    union bpf_attr a = {};
    a.map_type = host_info.type;
    a.key_size = host_info.key_size;
    a.value_size = host_info.value_size;
    a.max_entries = host_info.max_entries;
    a.map_flags = host_info.map_flags;
    snprintf(a.map_name, sizeof(a.map_name), "iris_uid");
    int fd = bpf(BPF_MAP_CREATE, &a);
    if (fd < 0) fail("create private UID table");
    return fd;
}
static void validate_program(void) {
    uint32_t maps[3] = {};
    struct bpf_prog_info i = {.nr_map_ids = 3, .map_ids = (uint64_t)(uintptr_t)maps};
    info(program_fd, &i, sizeof(i));
    if (i.type != BPF_PROG_TYPE_CGROUP_SOCK || i.nr_map_ids != 2 ||
        !((maps[0] == map_id(host_fd) && maps[1] == map_id(outer_fd)) ||
          (maps[1] == map_id(host_fd) && maps[0] == map_id(outer_fd)))) {
        errno = EINVAL; fail("dispatcher map ownership");
    }
}
/* pin让对象跨helper退出保留；核原厂身份、map类型/尺寸及程序引用的map ID。
 * 路径相同不是充分证明，不能覆盖未知策略。
 */
static void ensure_objects(void) {
    if (mkdir(PINS, 0700) && errno != EEXIST) fail("pin directory");
    int dir = open(PINS, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (dir < 0 || fstat(dir, &st) || st.st_uid != 0 || (st.st_mode & 077)) {
        errno = EACCES; fail("private pin directory ownership");
    }
    close(dir);
    original_fd = obj_get(ORIGINAL);
    if (original_fd < 0) {
        if (errno != ENOENT) fail("saved original");
        original_fd = obj_get(STOCK_PROG);
        if (original_fd < 0) fail("stock program pin");
        if (attached() != prog_id(original_fd)) { errno = EBUSY; fail("stock attachment mismatch"); }
        pin(original_fd, ORIGINAL);
    }
    int stock = obj_get(STOCK_PROG);
    if (stock < 0 || prog_id(stock) != prog_id(original_fd)) {
        errno = EBUSY; fail("stock program identity changed");
    }
    close(stock);
    outer_fd = obj_get(OUTER);
    if (outer_fd < 0) {
        if (errno != ENOENT) fail("domain map pin");
        union bpf_attr a = {};
        a.map_type = BPF_MAP_TYPE_HASH_OF_MAPS;
        a.key_size = 8;
        a.value_size = 4;
        a.max_entries = 128;
        a.inner_map_fd = (uint32_t)host_fd;
        snprintf(a.map_name, sizeof(a.map_name), "iris_domains");
        outer_fd = bpf(BPF_MAP_CREATE, &a);
        if (outer_fd < 0) fail("create domain map");
        pin(outer_fd, OUTER);
    }
    struct bpf_map_info oi = {};
    info(outer_fd, &oi, sizeof(oi));
    if (oi.type != BPF_MAP_TYPE_HASH_OF_MAPS || oi.key_size != 8 || oi.value_size != 4 || oi.max_entries != 128) {
        errno = EINVAL; fail("domain map format");
    }
    program_fd = obj_get(PROGRAM);
    if (program_fd < 0) {
        if (errno != ENOENT) fail("dispatcher pin");
        struct bpf_insn instructions[sizeof(iris_instructions) / sizeof(iris_instructions[0])];
        memcpy(instructions, iris_instructions, sizeof(instructions));
        instructions[SLOT_IRIS_DOMAINS].src_reg = BPF_PSEUDO_MAP_FD;
        instructions[SLOT_IRIS_DOMAINS].imm = outer_fd;
        instructions[SLOT_HOST_PERMISSIONS].src_reg = BPF_PSEUDO_MAP_FD;
        instructions[SLOT_HOST_PERMISSIONS].imm = host_fd;
        char *log = calloc(1, 65536);
        if (!log) fail("verifier log allocation");
        union bpf_attr a = {};
        a.prog_type = BPF_PROG_TYPE_CGROUP_SOCK;
        a.expected_attach_type = BPF_CGROUP_INET_SOCK_CREATE;
        a.insn_cnt = (uint32_t)(sizeof(instructions) / sizeof(instructions[0]));
        a.insns = (uint64_t)(uintptr_t)instructions;
        a.license = (uint64_t)(uintptr_t)"GPL";
        a.log_level = 1;
        a.log_size = 65536;
        a.log_buf = (uint64_t)(uintptr_t)log;
        snprintf(a.prog_name, sizeof(a.prog_name), "iris_inet_v1");
        program_fd = bpf(BPF_PROG_LOAD, &a);
        if (program_fd < 0) { int error = errno; fprintf(stderr, "%s\n", log); errno = error; fail("BPF verifier"); }
        free(log);
        pin(program_fd, PROGRAM);
    }
    validate_program();
}
static void ensure_attached(void) {
    uint32_t id = attached();
    if (id == prog_id(program_fd)) return;
    if (id != prog_id(original_fd)) { errno = EBUSY; fail("unrecognized Host policy; refusing replacement"); }
    attach(program_fd);
}
static uint64_t cookie(void) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) fail("netns control socket");
    uint64_t c = 0;
    socklen_t len = sizeof(c);
    if (getsockopt(fd, SOL_SOCKET, SO_NETNS_COOKIE, &c, &len) || len != sizeof(c) || !c) fail("netns cookie");
    close(fd);
    return c;
}
static uint64_t ticks(unsigned pid) {
    char path[64], buf[4096];
    snprintf(path, sizeof(path), "/proc/%u/stat", pid);
    FILE *f = fopen(path, "r");
    if (!f || !fgets(buf, sizeof(buf), f)) fail("PID start time");
    fclose(f);
    char *p = strrchr(buf, ')');
    if (!p) { errno = EINVAL; fail("PID stat format"); }
    char *save = NULL, *token = strtok_r(p + 1, " ", &save);
    for (int field = 3; field < 22 && token; field++) token = strtok_r(NULL, " ", &save);
    if (!token) { errno = EINVAL; fail("PID stat fields"); }
    return number(token);
}
/* 临时进入Guest netns读SO_NETNS_COOKIE后恢复Host，前后核start tick。
 * cookie不是UID、浏览器cookie或GSF ID。
 */
static uint64_t guest_cookie(unsigned pid, uint64_t tick) {
    if (pid <= 1 || ticks(pid) != tick) { errno = ESRCH; fail("stale Guest PID"); }
    int host = open("/proc/self/ns/net", O_RDONLY | O_CLOEXEC);
    char path[64];
    snprintf(path, sizeof(path), "/proc/%u/ns/net", pid);
    int guest = open(path, O_RDONLY | O_CLOEXEC);
    struct stat hs, gs;
    if (host < 0 || guest < 0 || fstat(host, &hs) || fstat(guest, &gs) || hs.st_ino == gs.st_ino) {
        errno = EINVAL; fail("private Guest network namespace");
    }
    if (setns(guest, CLONE_NEWNET)) fail("enter Guest network");
    uint64_t c = cookie();
    if (setns(host, CLONE_NEWNET)) fail("restore Host network");
    close(host); close(guest);
    if (ticks(pid) != tick) { errno = ESRCH; fail("Guest PID changed"); }
    return c;
}
static int domain(uint64_t c, uint32_t *id) {
    union bpf_attr a = {.map_fd = (uint32_t)outer_fd,
        .key = (uint64_t)(uintptr_t)&c, .value = (uint64_t)(uintptr_t)id};
    return bpf(BPF_MAP_LOOKUP_ELEM, &a);
}
static void guest_path(char *path, size_t size, unsigned id, uint64_t c) {
    if (id < 1007 || id > 1134 || !c) { errno = EINVAL; fail("IrisBox Guest record"); }
    snprintf(path, size, PINS "/guest_%u_%llu", id, (unsigned long long)c);
}
/* 建表后先输出cookie/map ID/pin并flush，供network失败账本回收，再插域映射。
 * 这个输出是协议，不是任意日志；改格式须同步network/inner。
 */
static void register_guest(unsigned id, unsigned pid, uint64_t tick) {
    ensure_attached();
    uint64_t c = guest_cookie(pid, tick);
    uint32_t old;
    if (!domain(c, &old) || errno != ENOENT) { errno = EEXIST; fail("Guest domain already registered"); }
    char path[256];
    guest_path(path, sizeof(path), id, c);
    int fd = create_uid_map();
    pin(fd, path);
    // Journal ownership before inserting: failed setup can remove only this map.
    printf("%llu %u %s\n", (unsigned long long)c, map_id(fd), path);
    fflush(stdout);
    /* Ownership follows the verified kernel UID/GID maps, not a UID supplied
     * by Guest IPC. Unmapped and previous-project ranges are rejected.
     */
    unsigned owners[2];
    for (unsigned i = 0; i < 2; ++i) {
        char mapping[128], extra; unsigned long long inside, outside, span;
        snprintf(mapping, sizeof(mapping), "/proc/%u/%s_map", pid, i ? "gid" : "uid");
        FILE *f = fopen(mapping, "re");
        if (!f || fscanf(f, "%llu %llu %llu", &inside, &outside, &span) != 3 ||
            fscanf(f, " %c", &extra) == 1 || inside ||
            !(outside >= 2000000000ULL && outside < 2012800000ULL && outside % 100000 == 0 && span == 100000)) {
            errno = EINVAL; fail("Guest UID/GID map format");
        }
        fclose(f); owners[i] = (unsigned)outside + 1000;
    }
    if (owners[0] != owners[1] || ticks(pid) != tick) { errno = ESTALE; fail("Guest mapping changed"); }
    if (chown(path, owners[0], owners[1]) || chmod(path, 0660)) fail("Guest UID table access");
    uint32_t value = (uint32_t)fd;
    union bpf_attr a = {.map_fd = (uint32_t)outer_fd, .key = (uint64_t)(uintptr_t)&c,
        .value = (uint64_t)(uintptr_t)&value, .flags = BPF_NOEXIST};
    if (bpf(BPF_MAP_UPDATE_ELEM, &a)) fail("register private domain");
    close(fd);
}
/* 双核cookie映射和pin的map ID；已不存在可重复清理，身份不符拒绝删除。
 */
static void remove_guest(unsigned instance, uint64_t c, uint32_t expected) {
    char path[256];
    guest_path(path, sizeof(path), instance, c);
    uint32_t id = 0;
    int found = domain(c, &id);
    if (found && errno != ENOENT) fail("read Guest domain");
    if (!found && id != expected) { errno = EBUSY; fail("Guest domain belongs to another map"); }
    int fd = obj_get(path);
    if (fd < 0 && errno != ENOENT) fail("Guest table pin");
    if (fd >= 0 && map_id(fd) != expected) { errno = EBUSY; fail("Guest pin ownership"); }
    if (!found) {
        union bpf_attr a = {.map_fd = (uint32_t)outer_fd, .key = (uint64_t)(uintptr_t)&c};
        if (bpf(BPF_MAP_DELETE_ELEM, &a)) fail("unregister Guest");
    }
    if (fd >= 0) { close(fd); if (unlink(path)) fail("remove Guest pin"); }
}
/* probe只换真实UID测IPv4/IPv6 socket创建，不访问公网，不证明DNS/TLS/Google成功。
 */
static void sockets(unsigned uid) {
    fflush(stdout);
    pid_t child = fork();
    if (child < 0) fail("probe fork");
    if (!child) {
        if (setgroups(0, NULL) || setgid(uid) || setuid(uid)) fail("probe UID");
        for (int af = AF_INET; af <= AF_INET6; af += AF_INET6 - AF_INET) {
            errno = 0;
            int fd = socket(af, SOCK_STREAM | SOCK_CLOEXEC, 0);
            printf("uid=%u family=%d socket=%s errno=%d\n", uid, af, fd < 0 ? "DENIED" : "OK", fd < 0 ? errno : 0);
            if (fd >= 0) close(fd);
        }
        fflush(stdout); _exit(0);
    }
    int status;
    if (waitpid(child, &status, 0) < 0 || !WIFEXITED(status) || WEXITSTATUS(status)) exit(3);
}
int main(int argc, char **argv) {
    if (geteuid() || argc < 2) return 2;
    if (!strcmp(argv[1], "probe") && argc == 3) { sockets((unsigned)number(argv[2])); return 0; }
    struct stat self, host;
    if (stat("/proc/self/ns/net", &self) || stat("/proc/1/ns/net", &host) || self.st_ino != host.st_ino) return 3;
    // Scope and serialize all policy/map mutations through the established Host runtime.
    int lock = open(BASE "/inet_policy.lock", O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (lock < 0 || flock(lock, LOCK_EX)) fail("Host policy lock");
    root_fd = open("/sys/fs/cgroup", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    host_fd = obj_get(HOST_MAP);
    if (root_fd < 0 || host_fd < 0) fail("Host policy objects");
    info(host_fd, &host_info, sizeof(host_info));
    if (host_info.type != BPF_MAP_TYPE_HASH || host_info.key_size != 4 || host_info.value_size != 1) {
        errno = EINVAL; fail("Host UID permission format");
    }
    if (!strcmp(argv[1], "inspect") && argc == 2) {
        printf("attached=%u host_map=%u type=%u key=%u value=%u max=%u flags=%u cookie=%llu\n",
            attached(), host_info.id, host_info.type, host_info.key_size, host_info.value_size,
            host_info.max_entries, host_info.map_flags, (unsigned long long)cookie());
        return 0;
    }
    int existing_dispatcher = access(PROGRAM, F_OK) == 0;
    ensure_objects();
    if (existing_dispatcher && attached() != prog_id(program_fd)) {
        errno = EBUSY; fail("existing dispatcher changed; refusing Host policy replacement");
    }
    if (!strcmp(argv[1], "verify") && argc == 2) {
        printf("verified=%u attached=%u original=%u host_map=%u domains=%u\n",
            prog_id(program_fd), attached(), prog_id(original_fd), map_id(host_fd), map_id(outer_fd));
    } else if (!strcmp(argv[1], "ensure") && argc == 2) {
        ensure_attached();
    } else if (!strcmp(argv[1], "register") && argc == 5) {
        register_guest((unsigned)number(argv[2]), (unsigned)number(argv[3]), number(argv[4]));
    } else if (!strcmp(argv[1], "remove") && argc == 5) {
        remove_guest((unsigned)number(argv[2]), number(argv[3]), (uint32_t)number(argv[4]));
    /* restore要求无登记域，只恢复原厂socket策略，不还原脚本、ROM或账号。
     */
    } else return 2;
    return 0;
}
