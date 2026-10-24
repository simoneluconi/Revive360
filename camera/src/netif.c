/*
 * Network interface primitives (the camera has /sbin/ifconfig, but it is not on
 * the hook's PATH and we prefer not to depend on it) plus the Broadcom dhd
 * ASCII private command used by the stock mobileap-agent to start the softAP:
 *
 *   ioctl(sock, 0x8B0C, &ifr) with ifr.ifr_data = &(struct android_wifi_priv_cmd)
 *       { char *buf; int used_len; int total_len; }
 *   commands: "ASCII_CMD=AP_CFG,SSID_LEN=..,SSID=..,SEC=..,KEY_LEN=..,KEY=..,
 *              CHANNEL=..,PREAMBLE=0,MAX_SCB=..,HIDE=..,END"
 *             "ASCII_CMD=AP_BSS_START" (the AP iface wl0.1 appears)
 *             "ASCII_CMD=AP_BSS_STOP"
 * (docs/firmware/09_camera_hw_control.md, RE of mobileap-agent).
 *
 * Exposed as subcommands so the SD launcher script can orchestrate the
 * bring-up strategies without a rebuild:
 *   g360rd mac <if>                  print MAC (aa:bb:cc:dd:ee:ff)
 *   g360rd ssid <if> [prefix]        print G360R-XXXXXX from the MAC
 *   g360rd ifflags <if>              print flags
 *   g360rd ifup|ifdown <if>
 *   g360rd ifaddr <if> <ip> <mask>   set address + netmask, bring up
 *   g360rd waitif <if> <seconds>     wait for /sys/class/net/<if>
 *   g360rd privcmd <if> <ascii-cmd> [ioctl-number] [android|iwpoint]
 */
#include "g360rd.h"

#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

/* struct iw_point from <linux/wireless.h>, declared locally to avoid pulling
 * kernel headers into the build. 32-bit: pointer(4) + u16 length + u16 flags. */
struct g_iw_point {
    void    *pointer;
    uint16_t length;
    uint16_t flags;
};

/* bcmdhd's struct android_wifi_priv_cmd (32-bit layout on the camera). */
struct g_android_priv_cmd {
    char *buf;
    int   used_len;
    int   total_len;
};

#define PRIV_BUF_LEN 0x100   /* stock total_len */

static int sock_open(void)
{
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0)
        LOGE("netif: socket: %s", strerror(errno));
    return s;
}

static void ifr_setname(struct ifreq *ifr, const char *ifname)
{
    memset(ifr, 0, sizeof *ifr);
    snprintf(ifr->ifr_name, IFNAMSIZ, "%s", ifname);
}

int netif_get_mac(const char *ifname, unsigned char mac[6])
{
    struct ifreq ifr;
    int s = sock_open(), rc;
    if (s < 0)
        return -1;
    ifr_setname(&ifr, ifname);
    rc = ioctl(s, SIOCGIFHWADDR, &ifr);
    close(s);
    if (rc < 0)
        return -1;
    memcpy(mac, ifr.ifr_hwaddr.sa_data, 6);
    return 0;
}

int netif_mac_str(const char *ifname, char *out, size_t outlen)
{
    unsigned char m[6];
    if (netif_get_mac(ifname, m) < 0)
        return -1;
    snprintf(out, outlen, "%02x:%02x:%02x:%02x:%02x:%02x", m[0], m[1], m[2], m[3], m[4], m[5]);
    return 0;
}

int netif_get_flags(const char *ifname, int *flags)
{
    struct ifreq ifr;
    int s = sock_open(), rc;
    if (s < 0)
        return -1;
    ifr_setname(&ifr, ifname);
    rc = ioctl(s, SIOCGIFFLAGS, &ifr);
    close(s);
    if (rc < 0)
        return -1;
    *flags = ifr.ifr_flags;
    return 0;
}

int netif_set_updown(const char *ifname, int up)
{
    struct ifreq ifr;
    int s = sock_open(), rc;
    if (s < 0)
        return -1;
    ifr_setname(&ifr, ifname);
    rc = ioctl(s, SIOCGIFFLAGS, &ifr);
    if (rc == 0) {
        if (up)
            ifr.ifr_flags |= IFF_UP | IFF_RUNNING;
        else
            ifr.ifr_flags &= ~IFF_UP;
        rc = ioctl(s, SIOCSIFFLAGS, &ifr);
    }
    if (rc < 0)
        LOGE("netif: %s %s: %s", ifname, up ? "up" : "down", strerror(errno));
    close(s);
    return rc;
}

static int set_inaddr(int s, const char *ifname, unsigned long req, const char *ip)
{
    struct ifreq ifr;
    struct sockaddr_in *sin = (struct sockaddr_in *)&ifr.ifr_addr;
    ifr_setname(&ifr, ifname);
    sin->sin_family = AF_INET;
    if (inet_pton(AF_INET, ip, &sin->sin_addr) != 1) {
        LOGE("netif: bad address %s", ip);
        errno = EINVAL;
        return -1;
    }
    return ioctl(s, req, &ifr);
}

int netif_set_addr(const char *ifname, const char *ip, const char *mask)
{
    int s = sock_open(), rc;
    if (s < 0)
        return -1;
    rc = set_inaddr(s, ifname, SIOCSIFADDR, ip);
    if (rc < 0)
        LOGE("netif: SIOCSIFADDR %s %s: %s", ifname, ip, strerror(errno));
    if (rc == 0) {
        rc = set_inaddr(s, ifname, SIOCSIFNETMASK, mask);
        if (rc < 0)
            LOGE("netif: SIOCSIFNETMASK %s %s: %s", ifname, mask, strerror(errno));
    }
    close(s);
    if (rc == 0)
        rc = netif_set_updown(ifname, 1);
    return rc;
}

int netif_priv_cmd(const char *ifname, unsigned long req, int style,
                   const char *cmd, char *reply, size_t replylen)
{
    /* The driver may write a reply into the buffer, so it is >= total_len. */
    char buf[PRIV_BUF_LEN * 4];
    struct ifreq ifr;
    struct g_android_priv_cmd pc;
    struct g_iw_point *iwp;
    int s, rc;
    size_t n = strlen(cmd);

    if (n + 1 > PRIV_BUF_LEN) {
        LOGE("netif: private command too long (%zu)", n);
        return -1;
    }
    memset(buf, 0, sizeof buf);
    memcpy(buf, cmd, n);
    s = sock_open();
    if (s < 0)
        return -1;
    ifr_setname(&ifr, ifname);
    if (style == 0) {
        pc.buf = buf;
        pc.used_len = (int)n + 1;
        pc.total_len = PRIV_BUF_LEN;
        ifr.ifr_data = (char *)&pc;
    } else {
        /* struct iwreq overlays ifreq: name, then the iw_point union member. */
        iwp = (struct g_iw_point *)&ifr.ifr_ifru;
        iwp->pointer = buf;
        /* The whole buffer, as mobileap-agent passes it (not just the command) */
        iwp->length = (uint16_t)PRIV_BUF_LEN;
        iwp->flags = 0;
    }
    usleep(250000); /* the stock wrapper sleeps 250 ms before each command */
    rc = ioctl(s, req, &ifr);
    {
        int e = errno;
        if (rc < 0)
            LOGE("netif: priv ioctl 0x%lx style=%d on %s failed: %s", req, style, ifname, strerror(e));
        else
            LOGI("netif: priv ioctl 0x%lx style=%d on %s ok (rc=%d)", req, style, ifname, rc);
        close(s);
        if (reply && replylen) {
            buf[sizeof buf - 1] = 0;
            snprintf(reply, replylen, "%s", buf);
        }
        errno = e;
    }
    return rc;
}

/* ------------------------------------------------------------- CLI */

static int usage_net(void)
{
    fprintf(stderr,
            "usage: g360rd mac <if> | ssid <if> [prefix] | ifflags <if> | ifup <if> | ifdown <if>\n"
            "       g360rd ifaddr <if> <ip> <mask> | waitif <if> <seconds>\n"
            "       g360rd privcmd <if> <ascii-cmd> [ioctl-number] [android|iwpoint]\n");
    return 2;
}

int cmd_net(int argc, char **argv)
{
    const char *sub = argv[0];
    char tmp[512];
    if (argc < 2)
        return usage_net();

    if (strcmp(sub, "mac") == 0) {
        if (netif_mac_str(argv[1], tmp, sizeof tmp) < 0) {
            fprintf(stderr, "mac %s: %s\n", argv[1], strerror(errno));
            return 1;
        }
        printf("%s\n", tmp);
        return 0;
    }
    if (strcmp(sub, "ssid") == 0) {
        unsigned char m[6];
        const char *prefix = argc > 2 ? argv[2] : "G360R-";
        if (netif_get_mac(argv[1], m) < 0) {
            fprintf(stderr, "ssid %s: %s\n", argv[1], strerror(errno));
            return 1;
        }
        printf("%s%02X%02X%02X\n", prefix, m[3], m[4], m[5]);
        return 0;
    }
    if (strcmp(sub, "ifflags") == 0) {
        int f;
        if (netif_get_flags(argv[1], &f) < 0) {
            fprintf(stderr, "ifflags %s: %s\n", argv[1], strerror(errno));
            return 1;
        }
        printf("0x%04x%s%s\n", f, (f & IFF_UP) ? " UP" : "", (f & IFF_RUNNING) ? " RUNNING" : "");
        return 0;
    }
    if (strcmp(sub, "ifup") == 0 || strcmp(sub, "ifdown") == 0)
        return netif_set_updown(argv[1], sub[2] == 'u') < 0 ? 1 : 0;
    if (strcmp(sub, "ifaddr") == 0) {
        if (argc < 4)
            return usage_net();
        return netif_set_addr(argv[1], argv[2], argv[3]) < 0 ? 1 : 0;
    }
    if (strcmp(sub, "waitif") == 0) {
        int secs = argc > 2 ? atoi(argv[2]) : 10;
        int64_t end = now_ms_mono() + (int64_t)secs * 1000;
        snprintf(tmp, sizeof tmp, "/sys/class/net/%s", argv[1]);
        while (!dir_exists(tmp)) {
            if (now_ms_mono() >= end)
                return 1;
            sleep_ms(200);
        }
        return 0;
    }
    if (strcmp(sub, "privcmd") == 0) {
        unsigned long req = 0x8B0C;
        int style = 0, rc;
        char reply[1024];
        if (argc < 3)
            return usage_net();
        if (argc > 3)
            req = strtoul(argv[3], NULL, 0);
        if (argc > 4 && strcmp(argv[4], "iwpoint") == 0)
            style = 1;
        rc = netif_priv_cmd(argv[1], req, style, argv[2], reply, sizeof reply);
        printf("rc=%d errno=%s reply=[%s]\n", rc, rc < 0 ? strerror(errno) : "0", reply);
        return rc < 0 ? 1 : 0;
    }
    return usage_net();
}
