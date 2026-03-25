/*
 * cgroup_fix.c — LD_PRELOAD shim for ES 5.0–5.2 on cgroups v2
 *
 * ES 5.0–5.2 reads /proc/self/cgroup expecting cgroups v1 format.
 * On cgroups v2, the "0::" line causes a parse failure. This shim intercepts
 * open/fopen calls for /proc/self/cgroup and redirects them to a filtered
 * copy that strips the v2 line.
 *
 * Built with: gcc -shared -fPIC -o libcgroup_fix.so cgroup_fix.c -ldl
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <unistd.h>

static const char *real_path = "/proc/self/cgroup";
static const char *filtered_path = "/tmp/cgroup_filtered";

static const char *fix(const char *p) {
    return (p && strcmp(p, real_path) == 0) ? filtered_path : p;
}

typedef int (*open_t)(const char *, int, ...);

int open(const char *p, int f, ...) {
    open_t r = (open_t)dlsym(RTLD_NEXT, "open");
    mode_t m = 0;
    if (f & O_CREAT) { va_list a; va_start(a, f); m = va_arg(a, mode_t); va_end(a); }
    return r(fix(p), f, m);
}

int open64(const char *p, int f, ...) {
    open_t r = (open_t)dlsym(RTLD_NEXT, "open64");
    mode_t m = 0;
    if (f & O_CREAT) { va_list a; va_start(a, f); m = va_arg(a, mode_t); va_end(a); }
    return r(fix(p), f, m);
}

int openat(int d, const char *p, int f, ...) {
    typedef int (*fn)(int, const char *, int, ...);
    fn r = (fn)dlsym(RTLD_NEXT, "openat");
    mode_t m = 0;
    if (f & O_CREAT) { va_list a; va_start(a, f); m = va_arg(a, mode_t); va_end(a); }
    return r(d, fix(p), f, m);
}

typedef FILE *(*fopen_t)(const char *, const char *);

FILE *fopen(const char *p, const char *m) {
    fopen_t r = (fopen_t)dlsym(RTLD_NEXT, "fopen");
    return r(fix(p), m);
}

FILE *fopen64(const char *p, const char *m) {
    fopen_t r = (fopen_t)dlsym(RTLD_NEXT, "fopen64");
    return r(fix(p), m);
}
