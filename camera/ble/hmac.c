/* SHA-256 (FIPS 180-4) and HMAC-SHA256 (RFC 2104): the Bluetooth helper's auth. */
#include "hmac.h"

#include <string.h>

typedef struct {
    uint32_t h[8];
    uint8_t block[64];
    size_t used;
    uint64_t bits;
} sha256_t;

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void compress(sha256_t *s)
{
    uint32_t w[64], a, b, c, d, e, f, g, h, t1, t2;
    int i;

    for (i = 0; i < 16; i++)
        w[i] = (uint32_t)s->block[4 * i] << 24 | (uint32_t)s->block[4 * i + 1] << 16 |
               (uint32_t)s->block[4 * i + 2] << 8 | s->block[4 * i + 3];
    for (i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    a = s->h[0]; b = s->h[1]; c = s->h[2]; d = s->h[3];
    e = s->h[4]; f = s->h[5]; g = s->h[6]; h = s->h[7];
    for (i = 0; i < 64; i++) {
        t1 = h + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
        t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    s->h[0] += a; s->h[1] += b; s->h[2] += c; s->h[3] += d;
    s->h[4] += e; s->h[5] += f; s->h[6] += g; s->h[7] += h;
}

static void sha_init(sha256_t *s)
{
    static const uint32_t iv[8] = { 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                                    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19 };
    memcpy(s->h, iv, sizeof iv);
    s->used = 0;
    s->bits = 0;
}

static void sha_add(sha256_t *s, const uint8_t *p, size_t n)
{
    while (n--) {
        s->block[s->used++] = *p++;
        s->bits += 8;
        if (s->used == 64) {
            compress(s);
            s->used = 0;
        }
    }
}

static void sha_end(sha256_t *s, uint8_t out[32])
{
    uint64_t bits = s->bits;
    uint8_t pad = 0x80, zero = 0, len[8];
    int i;

    sha_add(s, &pad, 1);
    while (s->used != 56)
        sha_add(s, &zero, 1);
    for (i = 0; i < 8; i++)
        len[i] = (uint8_t)(bits >> (56 - 8 * i));
    sha_add(s, len, 8);
    for (i = 0; i < 8; i++) {
        out[4 * i] = (uint8_t)(s->h[i] >> 24);
        out[4 * i + 1] = (uint8_t)(s->h[i] >> 16);
        out[4 * i + 2] = (uint8_t)(s->h[i] >> 8);
        out[4 * i + 3] = (uint8_t)s->h[i];
    }
}

void hmac_sha256(const uint8_t *key, size_t keylen, const uint8_t *msg, size_t msglen, uint8_t out[32])
{
    uint8_t k[64], pad[64], inner[32];
    sha256_t s;
    int i;

    memset(k, 0, sizeof k);
    if (keylen > 64) {
        sha_init(&s);
        sha_add(&s, key, keylen);
        sha_end(&s, k);
    } else {
        memcpy(k, key, keylen);
    }
    for (i = 0; i < 64; i++)
        pad[i] = k[i] ^ 0x36;
    sha_init(&s);
    sha_add(&s, pad, 64);
    sha_add(&s, msg, msglen);
    sha_end(&s, inner);
    for (i = 0; i < 64; i++)
        pad[i] = k[i] ^ 0x5c;
    sha_init(&s);
    sha_add(&s, pad, 64);
    sha_add(&s, inner, 32);
    sha_end(&s, out);
}
