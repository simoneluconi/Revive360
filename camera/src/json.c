/*
 * JSON helpers.
 *
 * Output: we build responses with sbuf_printf and json_str (escaping).
 * Input: request bodies are small (<= 4 KiB) flat objects such as
 * {"mode":"photo","quality":"sfine"}. jobj_parse() validates the whole
 * document (strict RFC 8259 grammar) and keeps the top-level members;
 * nested arrays/objects are validated and recorded as JV_OTHER.
 */
#include "g360rd.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void json_str(sbuf_t *b, const char *s)
{
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    sbuf_append(b, "\"", 1);
    for (; *p; p++) {
        switch (*p) {
        case '"':  sbuf_append(b, "\\\"", 2); break;
        case '\\': sbuf_append(b, "\\\\", 2); break;
        case '\n': sbuf_append(b, "\\n", 2); break;
        case '\r': sbuf_append(b, "\\r", 2); break;
        case '\t': sbuf_append(b, "\\t", 2); break;
        default:
            if (*p < 0x20)
                sbuf_printf(b, "\\u%04x", *p);
            else
                sbuf_append(b, p, 1);
        }
    }
    sbuf_append(b, "\"", 1);
}

/* ------------------------------------------------------------- parser */

typedef struct {
    const char *p, *end;
    int depth;
} jp_t;

static void ws(jp_t *j)
{
    while (j->p < j->end && (*j->p == ' ' || *j->p == '\t' || *j->p == '\n' || *j->p == '\r'))
        j->p++;
}

static int hexval(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Append a code point as UTF-8 into out (bounded). */
static void put_utf8(char *out, size_t cap, size_t *n, unsigned cp)
{
    char tmp[4];
    size_t k = 0, i;
    if (cp < 0x80) tmp[k++] = (char)cp;
    else if (cp < 0x800) { tmp[k++] = (char)(0xC0 | (cp >> 6)); tmp[k++] = (char)(0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { tmp[k++] = (char)(0xE0 | (cp >> 12)); tmp[k++] = (char)(0x80 | ((cp >> 6) & 0x3F)); tmp[k++] = (char)(0x80 | (cp & 0x3F)); }
    else { tmp[k++] = (char)(0xF0 | (cp >> 18)); tmp[k++] = (char)(0x80 | ((cp >> 12) & 0x3F)); tmp[k++] = (char)(0x80 | ((cp >> 6) & 0x3F)); tmp[k++] = (char)(0x80 | (cp & 0x3F)); }
    for (i = 0; i < k && *n + 1 < cap; i++)
        out[(*n)++] = tmp[i];
}

/* Parse a string at j->p (which points at '"'). out may be NULL. */
static int parse_string(jp_t *j, char *out, size_t cap)
{
    size_t n = 0;
    if (j->p >= j->end || *j->p != '"')
        return -1;
    j->p++;
    while (j->p < j->end) {
        unsigned char c = (unsigned char)*j->p++;
        if (c == '"') {
            if (out && cap)
                out[n < cap ? n : cap - 1] = 0;
            return 0;
        }
        if (c < 0x20)
            return -1;
        if (c == '\\') {
            if (j->p >= j->end)
                return -1;
            c = (unsigned char)*j->p++;
            switch (c) {
            case '"': case '\\': case '/':
                break;
            case 'b': c = '\b'; break;
            case 'f': c = '\f'; break;
            case 'n': c = '\n'; break;
            case 'r': c = '\r'; break;
            case 't': c = '\t'; break;
            case 'u': {
                unsigned cp = 0;
                int i;
                if (j->end - j->p < 4)
                    return -1;
                for (i = 0; i < 4; i++) {
                    int h = hexval(j->p[i]);
                    if (h < 0)
                        return -1;
                    cp = cp * 16 + (unsigned)h;
                }
                j->p += 4;
                /* Surrogate pair. */
                if (cp >= 0xD800 && cp <= 0xDBFF && j->end - j->p >= 6 && j->p[0] == '\\' && j->p[1] == 'u') {
                    unsigned lo = 0;
                    int ok = 1;
                    for (i = 0; i < 4; i++) {
                        int h = hexval(j->p[2 + i]);
                        if (h < 0) { ok = 0; break; }
                        lo = lo * 16 + (unsigned)h;
                    }
                    if (ok && lo >= 0xDC00 && lo <= 0xDFFF) {
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        j->p += 6;
                    }
                }
                if (out)
                    put_utf8(out, cap, &n, cp);
                continue;
            }
            default:
                return -1;
            }
        }
        if (out && n + 1 < cap)
            out[n++] = (char)c;
    }
    return -1;
}

static int parse_number(jp_t *j, double *out)
{
    const char *s = j->p;
    char tmp[64];
    size_t n;
    if (j->p < j->end && *j->p == '-')
        j->p++;
    if (j->p >= j->end)
        return -1;
    if (*j->p == '0') {
        j->p++;
    } else if (*j->p >= '1' && *j->p <= '9') {
        while (j->p < j->end && *j->p >= '0' && *j->p <= '9') j->p++;
    } else {
        return -1;
    }
    if (j->p < j->end && *j->p == '.') {
        j->p++;
        if (j->p >= j->end || *j->p < '0' || *j->p > '9') return -1;
        while (j->p < j->end && *j->p >= '0' && *j->p <= '9') j->p++;
    }
    if (j->p < j->end && (*j->p == 'e' || *j->p == 'E')) {
        j->p++;
        if (j->p < j->end && (*j->p == '+' || *j->p == '-')) j->p++;
        if (j->p >= j->end || *j->p < '0' || *j->p > '9') return -1;
        while (j->p < j->end && *j->p >= '0' && *j->p <= '9') j->p++;
    }
    n = (size_t)(j->p - s);
    if (n >= sizeof tmp)
        return -1;
    memcpy(tmp, s, n);
    tmp[n] = 0;
    if (out)
        *out = strtod(tmp, NULL);
    return 0;
}

static int lit(jp_t *j, const char *w)
{
    size_t n = strlen(w);
    if ((size_t)(j->end - j->p) < n || memcmp(j->p, w, n) != 0)
        return -1;
    j->p += n;
    return 0;
}

/* Validate/skip any value; returns its JV_ type or -1. */
static int parse_value(jp_t *j, char *sout, size_t scap, double *nout)
{
    ws(j);
    if (j->p >= j->end)
        return -1;
    switch (*j->p) {
    case '"':
        return parse_string(j, sout, scap) == 0 ? JV_STRING : -1;
    case 't':
        if (nout) *nout = 1;
        return lit(j, "true") == 0 ? JV_BOOL : -1;
    case 'f':
        if (nout) *nout = 0;
        return lit(j, "false") == 0 ? JV_BOOL : -1;
    case 'n':
        return lit(j, "null") == 0 ? JV_NULL : -1;
    case '[':
    case '{': {
        char close = (*j->p == '[') ? ']' : '}';
        int is_obj = (close == '}');
        if (++j->depth > 16)
            return -1;
        j->p++;
        ws(j);
        if (j->p < j->end && *j->p == close) {
            j->p++;
            j->depth--;
            return JV_OTHER;
        }
        for (;;) {
            if (is_obj) {
                ws(j);
                if (parse_string(j, NULL, 0) < 0) return -1;
                ws(j);
                if (j->p >= j->end || *j->p != ':') return -1;
                j->p++;
            }
            if (parse_value(j, NULL, 0, NULL) < 0)
                return -1;
            ws(j);
            if (j->p >= j->end) return -1;
            if (*j->p == ',') { j->p++; continue; }
            if (*j->p == close) { j->p++; break; }
            return -1;
        }
        j->depth--;
        return JV_OTHER;
    }
    default:
        return parse_number(j, nout) == 0 ? JV_NUMBER : -1;
    }
}

int jobj_parse(const char *text, size_t len, jobj_t *out)
{
    jp_t j = { text, text + len, 0 };
    memset(out, 0, sizeof *out);
    ws(&j);
    if (j.p >= j.end || *j.p != '{')
        return -1;
    j.p++;
    ws(&j);
    if (j.p < j.end && *j.p == '}') {
        j.p++;
        goto tail;
    }
    for (;;) {
        char key[64];
        char sval[128];
        double num = 0;
        int t;
        ws(&j);
        if (parse_string(&j, key, sizeof key) < 0)
            return -1;
        ws(&j);
        if (j.p >= j.end || *j.p != ':')
            return -1;
        j.p++;
        sval[0] = 0;
        t = parse_value(&j, sval, sizeof sval, &num);
        if (t < 0)
            return -1;
        if (out->n < JOBJ_MAX) {
            snprintf(out->m[out->n].key, sizeof out->m[0].key, "%s", key);
            out->m[out->n].type = t;
            snprintf(out->m[out->n].str, sizeof out->m[0].str, "%s", sval);
            out->m[out->n].num = num;
            out->n++;
        }
        ws(&j);
        if (j.p >= j.end)
            return -1;
        if (*j.p == ',') { j.p++; continue; }
        if (*j.p == '}') { j.p++; break; }
        return -1;
    }
tail:
    ws(&j);
    return (j.p == j.end) ? 0 : -1;
}

const char *jobj_get_str(const jobj_t *o, const char *key, int *present_wrong_type)
{
    int i;
    if (present_wrong_type)
        *present_wrong_type = 0;
    for (i = 0; i < o->n; i++) {
        if (strcmp(o->m[i].key, key) == 0) {
            if (o->m[i].type == JV_STRING)
                return o->m[i].str;
            /* null counts as "absent" for optional fields */
            if (o->m[i].type != JV_NULL && present_wrong_type)
                *present_wrong_type = 1;
            return NULL;
        }
    }
    return NULL;
}

int jobj_get_num(const jobj_t *o, const char *key, double *out)
{
    int i;
    for (i = 0; i < o->n; i++) {
        if (strcmp(o->m[i].key, key) == 0) {
            if (o->m[i].type == JV_NUMBER) {
                *out = o->m[i].num;
                return 1;
            }
            return o->m[i].type == JV_NULL ? 0 : -1;
        }
    }
    return 0;
}
