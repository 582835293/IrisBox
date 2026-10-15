/* Android 13 socket-create policy, with a private UID table per Guest netns.
 * Host semantics are unchanged: missing UID => INTERNET, mask 4 (1 << 2) => INTERNET.
 * PermissionMonitor maintains the selected table, including permission=0.
 * Only this hook is replaced; packet accounting/firewall hooks stay stock.
 */
typedef unsigned int u32;
typedef unsigned long long u64;
typedef unsigned char u8;
extern int iris_domains;
extern int host_permissions;
static void *(*lookup)(void *, const void *) = (void *)1;
static u64 (*get_uid_gid)(void) = (void *)15;
static u64 (*netns_cookie)(void *) = (void *)122;

/* 创建socket时按netns选表，再按UID % 100000查权限。
 * 无项沿用AOSP默认允许；无权限应用由原厂明确写0，更新/卸载也由它维护。
 * 空表而不接原厂维护会放错权限，不得硬编码放行GMS或10089。
 */
__attribute__((section("cgroup/sock"), used))
int iris_inet(void *ctx) {
    u64 cookie = netns_cookie(ctx);
    void *table = lookup(&iris_domains, &cookie);
    u32 appid = (u32)get_uid_gid() % 100000;
    u8 *permission = table ? lookup(table, &appid) : lookup(&host_permissions, &appid);
    return !permission || ((*permission & 4) != 0);
}
char iris_license[] __attribute__((section("license"), used)) = "GPL";
