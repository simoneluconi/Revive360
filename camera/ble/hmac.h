#ifndef HMAC_H
#define HMAC_H

#include <stddef.h>
#include <stdint.h>

void hmac_sha256(const uint8_t *key, size_t keylen, const uint8_t *msg, size_t msglen, uint8_t out[32]);

#endif
