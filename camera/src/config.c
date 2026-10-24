/*
 * Flat KEY=VALUE config file reader (the same file the launcher reads with
 * sed). Syntax: one KEY=VALUE per line, '#' starts a comment line, optional
 * single or double quotes around the value, CRLF tolerated (the user may edit
 * the file on Windows).
 */
#include "g360rd.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int config_load(config_t *c, const char *path)
{
    FILE *fp;
    char line[512];

    memset(c, 0, sizeof *c);
    if (!path || !*path)
        return -1;
    fp = fopen(path, "r");
    if (!fp)
        return -1;
    while (fgets(line, sizeof line, fp) && c->n < CFG_MAX) {
        char *eq, *k, *v;
        size_t vl;
        trim(line);
        if (!line[0] || line[0] == '#')
            continue;
        eq = strchr(line, '=');
        if (!eq)
            continue;
        *eq = 0;
        k = line;
        v = eq + 1;
        trim(k);
        trim(v);
        vl = strlen(v);
        if (vl >= 2 && ((v[0] == '"' && v[vl - 1] == '"') || (v[0] == '\'' && v[vl - 1] == '\''))) {
            v[vl - 1] = 0;
            v++;
        }
        snprintf(c->key[c->n], sizeof c->key[0], "%s", k);
        snprintf(c->val[c->n], sizeof c->val[0], "%s", v);
        c->n++;
    }
    fclose(fp);
    return 0;
}

const char *config_get(const config_t *c, const char *key, const char *def)
{
    int i;
    /* Last definition wins, like a shell would do. */
    for (i = c->n - 1; i >= 0; i--)
        if (strcmp(c->key[i], key) == 0)
            return c->val[i];
    return def;
}

int config_get_int(const config_t *c, const char *key, int def)
{
    const char *v = config_get(c, key, NULL);
    char *end;
    long n;
    if (!v || !*v)
        return def;
    n = strtol(v, &end, 0);
    return (*end == 0) ? (int)n : def;
}
