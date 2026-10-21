/* Host-only ownership preparation. Never follows Guest symlinks.
 * Overlay metacopy keeps ROM contents in the shared read-only lower layer.
 * Data remains private and writable; system/vendor are sealed before release.
 */
#include <dirent.h>
#include <endian.h>
#include <linux/magic.h>
#include <sys/vfs.h>
#include <sys/xattr.h>

static unsigned shifted(unsigned id, unsigned base) {
    if (id < 100000U) return base + id;
    if (id >= base && id < base + 100000U) return id;
    errno = ERANGE; die("unmapped filesystem identity"); return 0;
}
static void shift_acl(int fd, const char *name, unsigned base) {
    unsigned char acl[4096];
    ssize_t n = fgetxattr(fd, name, acl, sizeof(acl));
    if (n < 0 && (errno == ENODATA || errno == ENOTSUP)) return;
    if (n < 4 || (n - 4) % 8) die("POSIX ACL format");
    int changed = 0;
    for (ssize_t i = 4; i < n; i += 8) {
        uint16_t tag; uint32_t id;
        memcpy(&tag, acl + i, 2); tag = le16toh(tag);
        if (tag != 2 && tag != 8) continue; /* named user / named group */
        memcpy(&id, acl + i + 4, 4); id = le32toh(id);
        unsigned mapped = shifted(id, base);
        if (mapped != id) { id = htole32(mapped); memcpy(acl + i + 4, &id, 4); changed = 1; }
    }
    if (changed && fsetxattr(fd, name, acl, (size_t)n, 0)) die("map POSIX ACL");
}
static unsigned long shifted_files;
static int allow_prepared_devices;
static int private_persistent_tree;
static void shift_directory(int dir, dev_t device, unsigned base, unsigned depth, int metadata) {
    if (depth > 128 || shifted_files > 500000) { errno = E2BIG; die("ownership tree bounds"); }
    DIR *stream = fdopendir(dup(dir)); if (!stream) die("ownership directory");
    struct dirent *entry;
    while ((entry = readdir(stream))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        struct stat st;
        if (fstatat(dir, entry->d_name, &st, AT_SYMLINK_NOFOLLOW)) die("ownership lstat");
        /* OverlayFS can report the lower/upper st_dev for regular files.
         * Mount-boundary checks must use directories, otherwise ROM files are
         * silently skipped. Other mounted directories are handled explicitly.
         */
        if (S_ISDIR(st.st_mode) && st.st_dev != device) continue;
        /* Old Host-root data must not smuggle a raw disk/device node into
         * the mapped Guest. Only our freshly prepared /dev receives nodes.
         * Refuse migration instead of deleting or silently delegating it. */
        if (!allow_prepared_devices && (S_ISCHR(st.st_mode) || S_ISBLK(st.st_mode))) {
            errno = EPERM; die("device node outside prepared private /dev");
        }
        unsigned uid = shifted(st.st_uid, base), gid = shifted(st.st_gid, base);
        if (private_persistent_tree && S_ISREG(st.st_mode) && st.st_nlink > 1 &&
            (uid != st.st_uid || gid != st.st_gid)) {
            /* A legacy hardlink could alias an inode outside this data tree.
             * Never chown that inode. Migrate an independent verified copy. */
            errno = EMLINK; die("unshifted multi-link data inode requires independent copy");
        }
        int regular = S_ISREG(st.st_mode), directory = S_ISDIR(st.st_mode);
        int fd = -1;
        unsigned char caps[24] = {}; ssize_t cap_size = -1;
        if ((regular && metadata) || directory) {
            fd = openat(dir, entry->d_name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | (directory ? O_DIRECTORY : 0));
            if (fd < 0) die("open ownership inode");
            if (regular) {
                cap_size = fgetxattr(fd, "security.capability", caps, sizeof(caps));
                if (cap_size < 0 && errno != ENODATA && errno != ENOTSUP) die("read file capabilities");
            }
            if (directory) shift_directory(fd, device, base, depth + 1, metadata);
        }
        if (uid != st.st_uid || gid != st.st_gid) {
            if (fchownat(dir, entry->d_name, uid, gid, AT_SYMLINK_NOFOLLOW)) die("map inode owner");
            /* chown clears setid and file capabilities. Restore mode and
             * scope v2/v3 capabilities to this mapped root, never Host root.
             */
            if (fd >= 0 && fchmod(fd, st.st_mode & 07777)) die("restore inode mode");
            if (cap_size >= 0) {
                uint32_t magic; memcpy(&magic, caps, 4); magic = le32toh(magic);
                unsigned revision = magic & 0xff000000U;
                if ((revision == 0x02000000U && cap_size != 20) ||
                    (revision == 0x03000000U && cap_size != 24) ||
                    (revision != 0x02000000U && revision != 0x03000000U)) die("file capability revision");
                if (revision == 0x03000000U) {
                    uint32_t old; memcpy(&old, caps + 20, 4); old = le32toh(old);
                    if (old && old != base) { errno = ERANGE; die("foreign file capability root"); }
                }
                magic = htole32(0x03000000U | (magic & 1U)); memcpy(caps, &magic, 4);
                uint32_t rootid = htole32(base); memcpy(caps + 20, &rootid, 4);
                if (fsetxattr(fd, "security.capability", caps, sizeof(caps), 0)) die("map file capabilities");
            }
            ++shifted_files;
        }
        if (fd >= 0 && metadata) {
            shift_acl(fd, "system.posix_acl_access", base);
            if (directory) shift_acl(fd, "system.posix_acl_default", base);
        }
        if (fd >= 0) close(fd);
    }
    closedir(stream);
}
static void shift_tree(const char *path, unsigned base) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st)) die("ownership root");
    struct statfs fs;
    if (fstatfs(fd, &fs)) die("ownership filesystem");
    int metadata = fs.f_type != CGROUP_SUPER_MAGIC && fs.f_type != CGROUP2_SUPER_MAGIC;
    size_t path_size = strlen(path);
    allow_prepared_devices = path_size >= 4 && !strcmp(path + path_size - 4, "/dev");
    private_persistent_tree = (path_size >= 5 && !strcmp(path + path_size - 5, "/data")) ||
        (path_size >= 9 && !strcmp(path + path_size - 9, "/metadata"));
    shift_directory(fd, st.st_dev, base, 0, metadata);
    unsigned uid = shifted(st.st_uid, base), gid = shifted(st.st_gid, base);
    if (fchown(fd, uid, gid) || fchmod(fd, st.st_mode & 07777)) die("ownership root mode");
    if (metadata) {
        shift_acl(fd, "system.posix_acl_access", base);
        shift_acl(fd, "system.posix_acl_default", base);
    }
    close(fd);
}
