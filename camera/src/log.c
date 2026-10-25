/*
 * Logging: timestamped lines to a file on the SD card (and optionally stderr).
 *
 * The file is opened, appended to and closed for every line: an fd kept open on
 * the card would keep it busy, and the camera's power-off hangs when it cannot
 * unmount the card. It is rotated once (to <path>.1) when it grows past
 * LOG_ROTATE_BYTES so a long session cannot fill the card. fdatasync() is done
 * from the housekeeping thread so an abrupt power-off loses at most a couple of
 * seconds of log.
 */
#include "g360rd.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define LOG_ROTATE_BYTES (2L * 1024 * 1024)

static pthread_mutex_t g_log_mu = PTHREAD_MUTEX_INITIALIZER;
static char  g_log_path[512];
static int   g_log_stderr = 1;
static long  g_log_size;
static int   g_log_dirty;

void log_open(const char *path, int also_stderr)
{
    pthread_mutex_lock(&g_log_mu);
    g_log_stderr = also_stderr;
    g_log_path[0] = 0;
    if (path && *path) {
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd >= 0) {
            struct stat st;
            if (fstat(fd, &st) == 0)
                g_log_size = (long)st.st_size;
            close(fd);
            snprintf(g_log_path, sizeof g_log_path, "%s", path);
        } else {
            fprintf(stderr, "g360rd: cannot open log %s: %s\n", path, strerror(errno));
        }
    }
    pthread_mutex_unlock(&g_log_mu);
}

const char *log_path(void)
{
    return g_log_path[0] ? g_log_path : NULL;
}

static void rotate_locked(void)
{
    char old[600];
    snprintf(old, sizeof old, "%s.1", g_log_path);
    unlink(old);
    rename(g_log_path, old);
    g_log_size = 0;
}

void log_msg(int level, const char *fmt, ...)
{
    static const char lv[] = "EWID";
    char line[2048];
    struct timeval tv;
    struct tm tm;
    int n, written = 0;
    va_list ap;

    gettimeofday(&tv, NULL);
    localtime_r(&tv.tv_sec, &tm);
    n = snprintf(line, sizeof line, "%04d-%02d-%02d %02d:%02d:%02d.%03d %c ",
                 tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour,
                 tm.tm_min, tm.tm_sec, (int)(tv.tv_usec / 1000),
                 lv[level < 0 ? 0 : (level > 3 ? 3 : level)]);
    va_start(ap, fmt);
    vsnprintf(line + n, sizeof line - (size_t)n - 1, fmt, ap);
    va_end(ap);
    n = (int)strlen(line);
    if (n == 0 || line[n - 1] != '\n') {
        line[n++] = '\n';
        line[n] = 0;
    }

    pthread_mutex_lock(&g_log_mu);
    if (g_log_path[0]) {
        int fd;
        if (g_log_size + n > LOG_ROTATE_BYTES)
            rotate_locked();
        fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd >= 0) {
            if (write(fd, line, (size_t)n) == n) {
                g_log_size += n;
                g_log_dirty = 1;
                written = 1;
            }
            close(fd);
        }
    }
    if (g_log_stderr || !written)
        fputs(line, stderr);
    pthread_mutex_unlock(&g_log_mu);
}

void log_flush_sync(void)
{
    pthread_mutex_lock(&g_log_mu);
    if (g_log_path[0] && g_log_dirty) {
        int fd = open(g_log_path, O_WRONLY | O_APPEND | O_CLOEXEC);
        if (fd >= 0) {
            fdatasync(fd);
            close(fd);
        }
        g_log_dirty = 0;
    }
    pthread_mutex_unlock(&g_log_mu);
}

char *log_tail(int lines, size_t *len_out)
{
    /* Read at most the last 512 KiB; that comfortably holds 2000 lines. */
    const long window = 512 * 1024;
    char *buf = NULL, *start;
    long size, off;
    int fd, count = 0;
    ssize_t r;
    size_t got = 0;

    *len_out = 0;
    if (!g_log_path[0])
        return NULL;
    log_flush_sync();
    fd = open(g_log_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return NULL;
    size = (long)lseek(fd, 0, SEEK_END);
    off = size > window ? size - window : 0;
    buf = malloc((size_t)(size - off) + 1);
    if (!buf) {
        close(fd);
        return NULL;
    }
    while (got < (size_t)(size - off)) {
        r = pread(fd, buf + got, (size_t)(size - off) - got, off + (off_t)got);
        if (r <= 0)
            break;
        got += (size_t)r;
    }
    close(fd);
    buf[got] = 0;

    /* Walk back from the end counting newlines. A trailing newline does not
     * start a new line. */
    start = buf + got;
    if (start > buf && start[-1] == '\n')
        start--;
    while (start > buf) {
        if (start[-1] == '\n' && ++count >= lines)
            break;
        start--;
    }
    *len_out = got - (size_t)(start - buf);
    memmove(buf, start, *len_out);
    buf[*len_out] = 0;
    return buf;
}
