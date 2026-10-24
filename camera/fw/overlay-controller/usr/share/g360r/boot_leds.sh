#!/bin/sh
# Revive360 - firmware boot signature (g360r-leds.service, oneshot).
#
# Runs before di-camera-app, so the stock app sets its own LED state on top
# afterwards: nothing of the stock indication is overwritten. ~2.5 s: led.0 to
# led.4 light one at a time, twice round, then all flash twice. No signature =
# the stock rootfs is running. Every LED gets its trigger back at the end.
#
# Only led.0-4 (status RGB, front, rear). Not af (the bright AF assist lamp),
# card or wifi_red. LED-class nodes: writing trigger/brightness is safe. Which
# led.N is which colour is not pinned yet, so the pattern does not depend on it.

L=/sys/class/leds
LEDS=""
for n in 0 1 2 3 4; do
    [ -w "$L/led.$n/brightness" ] && LEDS="$LEDS led.$n"
done
[ -n "$LEDS" ] || exit 0

for l in $LEDS; do
    # The active trigger is the bracketed one: "[none] timer heartbeat"
    t=$(sed -n 's/.*\[\([^]]*\)\].*/\1/p' "$L/$l/trigger" 2>/dev/null)
    eval "trig_${l#led.}=\${t:-none}"
    eval "max_${l#led.}=$(cat "$L/$l/max_brightness" 2>/dev/null || echo 1)"
    echo none > "$L/$l/trigger" 2>/dev/null
    echo 0 > "$L/$l/brightness" 2>/dev/null
done

led() {   # led.N on|off
    if [ "$2" = on ]; then eval "v=\$max_${1#led.}"; else v=0; fi
    echo "$v" > "$L/$1/brightness" 2>/dev/null
}

for round in 1 2; do
    for l in $LEDS; do
        led "$l" on; sleep 0.15; led "$l" off
    done
done
for flash in 1 2; do
    for l in $LEDS; do led "$l" on; done
    sleep 0.2
    for l in $LEDS; do led "$l" off; done
    sleep 0.2
done

for l in $LEDS; do
    eval "t=\$trig_${l#led.}"
    echo "$t" > "$L/$l/trigger" 2>/dev/null
done
exit 0
