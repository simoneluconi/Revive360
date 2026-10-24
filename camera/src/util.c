/*
 * Small helpers: growable buffer, file I/O for sysfs-style files, time, and a
 * fork/exec runner with a hard timeout (every call into firmware tools such as
 * /usr/bin/st goes through run_cmd so a blocked tool cannot wedge us).
 */
#include "g360rd.h"

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------- sbuf */

void sbuf_init(sbuf_t *b)
{
    memset(b, 0, sizeof *b);
}

void sbuf_free(sbuf_t *b)
{
    free(b->buf);
    memset(b, 0, sizeof *b);
}

static int sbuf_reserve(sbuf_t *b, size_t extra)
{
    size_t need, ncap;
    char *nb;
    if (b->oom)
        return -1;
    need = b->len + extra + 1;
    if (need <= b->cap)
        return 0;
    ncap = b->cap ? b->cap : 256;
    while (ncap < need)
        ncap *= 2;
    nb = realloc(b->buf, ncap);
    if (!nb) {
        b->oom = 1;
        return -1;
    }
    b->buf = nb;
    b->cap = ncap;
    return 0;
}

void sbuf_append(sbuf_t *b, const void *data, size_t n)
{
    if (sbuf_reserve(b, n) < 0)
        return;
    memcpy(b->buf + b->len, data, n);
    b->len += n;
    b->buf[b->len] = 0;
}

void sbuf_puts(sbuf_t *b, const char *s)
{
    sbuf_append(b, s, strlen(s));
}

void sbuf_printf(sbuf_t *b, const char *fmt, ...)
{
    va_list ap;
    int n;
    char tmp[512];

    va_start(ap, fmt);
    n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if (n < 0)
        return;
    if ((size_t)n < sizeof tmp) {
        sbuf_append(b, tmp, (size_t)n);
        return;
    }
    if (sbuf_reserve(b, (size_t)n) < 0)
        return;
    va_start(ap, fmt);
    vsnprintf(b->buf + b->len, (size_t)n + 1, fmt, ap);
    va_end(ap);
    b->len += (size_t)n;
}

/* ------------------------------------------------------------- time */

int64_t now_ms_mono(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void sleep_ms(int ms)
{
    struct timespec ts;
    if (ms <= 0)
        return;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) < 0 && errno == EINTR)
        ;
}

/* ------------------------------------------------------------- files */

void trim(char *s)
{
    size_t n = strlen(s), i = 0;
    while (n > 0 && isspace((unsigned char)s[n - 1]))
        s[--n] = 0;
    while (s[i] && isspace((unsigned char)s[i]))
        i++;
    if (i)
        memmove(s, s + i, n - i + 1);
}

int read_text_file(const char *path, char *out, size_t outlen)
{
    int fd;
    ssize_t r;
    size_t got = 0;

    if (outlen == 0)
        return -1;
    out[0] = 0;
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return -1;
    while (got + 1 < outlen) {
        r = read(fd, out + got, outlen - 1 - got);
        if (r < 0 && errno == EINTR)
            continue;
        if (r <= 0)
            break;
        got += (size_t)r;
    }
    close(fd);
    out[got] = 0;
    trim(out);
    return (int)strlen(out);
}

int write_text_file(const char *path, const char *s)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC | O_TRUNC);
    size_t n = strlen(s);
    ssize_t w;
    if (fd < 0) {
        /* sysfs attributes exist already; regular files (sim) may not. */
        fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd < 0)
            return -1;
    }
    w = write(fd, s, n);
    close(fd);
    return (w == (ssize_t)n) ? 0 : -1;
}

int file_exists(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

int dir_exists(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

int mkdir_p(const char *path, mode_t mode)
{
    char tmp[512];
    size_t i, n = strlen(path);
    if (n == 0 || n >= sizeof tmp)
        return -1;
    memcpy(tmp, path, n + 1);
    for (i = 1; i <= n; i++) {
        if (tmp[i] == '/' || tmp[i] == 0) {
            char c = tmp[i];
            tmp[i] = 0;
            if (mkdir(tmp, mode) < 0 && errno != EEXIST)
                return -1;
            tmp[i] = c;
        }
    }
    return 0;
}

int str_ieq(const char *a, const char *b)
{
    return strcasecmp(a, b) == 0;
}

int str_iendswith(const char *s, const char *suffix)
{
    size_t n = strlen(s), m = strlen(suffix);
    return n >= m && strcasecmp(s + n - m, suffix) == 0;
}

/* ------------------------------------------------------------- run_cmd */

int run_cmd(char *const argv[], int timeout_ms, char *out, size_t outlen)
{
    int pfd[2] = { -1, -1 };
    pid_t pid;
    int status = 0, done = 0, rc;
    size_t got = 0;
    int64_t deadline = now_ms_mono() + timeout_ms;

    if (out && outlen)
        out[0] = 0;
    if (pipe(pfd) < 0)
        return -1;
    fcntl(pfd[0], F_SETFD, FD_CLOEXEC);

    pid = fork();
    if (pid < 0) {
        close(pfd[0]);
        close(pfd[1]);
        return -1;
    }
    if (pid == 0) {
        /* Child: own process group so a timeout kills grandchildren too.
         * Only async-signal-safe calls between fork and exec. */
        int devnull = open("/dev/null", O_RDONLY);
        setpgid(0, 0);
        if (devnull >= 0) {
            dup2(devnull, 0);
            close(devnull);
        }
        dup2(pfd[1], 1);
        dup2(pfd[1], 2);
        close(pfd[1]);
        signal(SIGPIPE, SIG_DFL);
        execv(argv[0], argv);
        _exit(127);
    }
    close(pfd[1]);
    setpgid(pid, pid); /* race-free: whichever of parent/child runs first */

    for (;;) {
        int64_t left = deadline - now_ms_mono();
        struct pollfd p = { .fd = pfd[0], .events = POLLIN };
        if (left <= 0)
            break;
        if (pfd[0] >= 0) {
            rc = poll(&p, 1, left > 100 ? 100 : (int)left);
            if (rc > 0) {
                char tmp[512];
                ssize_t r = read(pfd[0], tmp, sizeof tmp);
                if (r > 0) {
                    if (out && got + 1 < outlen) {
                        size_t take = (size_t)r;
                        if (take > outlen - 1 - got)
                            take = outlen - 1 - got;
                        memcpy(out + got, tmp, take);
                        got += take;
                        out[got] = 0;
                    }
                } else if (r == 0 || errno != EINTR) {
                    close(pfd[0]);
                    pfd[0] = -1;
                }
            }
        } else {
            sleep_ms(left > 50 ? 50 : (int)left);
        }
        rc = waitpid(pid, &status, WNOHANG);
        if (rc == pid) {
            done = 1;
            break;
        }
    }
    if (!done) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        waitpid(pid, &status, 0);
    }
    /* Drain what the child wrote just before exiting (non-blocking: a daemon
     * it spawned may keep the pipe open). */
    while (pfd[0] >= 0) {
        struct pollfd p = { .fd = pfd[0], .events = POLLIN };
        char tmp[512];
        ssize_t r;
        if (poll(&p, 1, 0) <= 0)
            break;
        r = read(pfd[0], tmp, sizeof tmp);
        if (r <= 0)
            break;
        if (out && got + 1 < outlen) {
            size_t take = (size_t)r;
            if (take > outlen - 1 - got)
                take = outlen - 1 - got;
            memcpy(out + got, tmp, take);
            got += take;
            out[got] = 0;
        }
    }
    if (pfd[0] >= 0)
        close(pfd[0]);
    if (out)
        trim(out);
    if (!done)
        return -2;
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    if (WIFSIGNALED(status))
        return 128 + WTERMSIG(status);
    return -1;
}
