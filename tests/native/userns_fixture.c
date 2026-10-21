/* Test-only DAC fixtures. No real Host secrets are exposed to the Guest. */
#define main irisbox_launcher_main_for_test
#include "../../engine/native/userns/rootvmos_userns.c"
#undef main

int main(int argc, char **argv) {
    host_only();
    if (argc != 8) return 2;
    unsigned id = number(argv[2]), pid = number(argv[3]), other = number(argv[5]);
    if (id < 1007 || id > 1134 || other < 1007 || other > 1134 || id == other ||
        strlen(argv[6]) != 32 || strspn(argv[6], "0123456789abcdef") != 32 || strcmp(argv[7], "fixture-v1")) return 2;
    verify_map(id, pid, strtoull(argv[4], NULL, 10), 0);
    unsigned base = profile(id), foreign = profile(other);
    if (base == foreign) return 3;
    int data = data_dir(id, base); char name[96];
    snprintf(name, sizeof(name), "rootvmos_userns_test_%s", argv[6]);
    if (!strcmp(argv[1], "create")) {
        if (mkdirat(data, name, 0700)) die("exclusive test directory");
        int dir = private_dir(data, name, base);
        stage_file(dir, "own", NULL, "guest-owned-test", base, 0600);
        stage_file(dir, "host", NULL, "host-owned-dummy", 0, 0600);
        stage_file(dir, "other", NULL, "foreign-owned-dummy", foreign, 0600);
        close(dir);
    } else if (!strcmp(argv[1], "remove")) {
        int dir = openat(data, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (dir < 0) die("test directory handle");
        const char *files[] = {"own", "host", "other"};
        for (unsigned i = 0; i < 3; ++i) if (unlinkat(dir, files[i], 0)) die("test unlink");
        close(dir); if (unlinkat(data, name, AT_REMOVEDIR)) die("test rmdir");
    } else return 2;
    close(data); return 0;
}
