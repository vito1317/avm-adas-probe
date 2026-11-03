#!/usr/bin/env python3
"""Record real Overpass API responses for MapAssist test fixtures.

Usage:
  record_overpass.py roads OUT.json S W N E      # same query MapSource.kt sends
  record_overpass.py raw OUT.json 'QUERY'         # arbitrary query (discovery)
Retries with exponential backoff and rotates between public mirrors.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]
UA = "AVM-ADAS-Probe/0.3 (map-assist test fixtures; +https://github.com/vito1317/avm-adas-probe)"

HW = ("motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|"
      "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link")


def roads_query(s, w, n, e):
    bb = "%.6f,%.6f,%.6f,%.6f" % (s, w, n, e)
    return ('[out:json][timeout:25];(way["highway"~"^(%s)$"]["area"!="yes"](%s);'
            'node["highway"="traffic_signals"](%s););out body;>;out skel qt;' % (HW, bb, bb))


def fetch(query, attempts=12):
    wait = 5.0
    for i in range(attempts):
        url = MIRRORS[i % len(MIRRORS)]
        try:
            data = urllib.parse.urlencode({"data": query}).encode()
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as r:
                body = r.read()
            obj = json.loads(body)
            if "elements" not in obj:
                raise ValueError("no elements")
            rem = obj.get("remark")
            if rem and ("timed out" in rem or "error" in rem.lower()):
                raise ValueError("remark: " + rem)
            print("ok from %s (%d bytes)" % (url, len(body)), file=sys.stderr)
            return obj
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, OSError) as ex:
            print("attempt %d %s failed: %s; sleep %.0fs" % (i + 1, url, ex, wait), file=sys.stderr)
            time.sleep(wait)
            wait = min(wait * 2, 120)
    raise SystemExit("all attempts failed")


def main():
    mode, out = sys.argv[1], sys.argv[2]
    if mode == "roads":
        s, w, n, e = map(float, sys.argv[3:7])
        q = roads_query(s, w, n, e)
    else:
        q = sys.argv[3]
    obj = fetch(q)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    els = obj["elements"]
    ways = [x for x in els if x["type"] == "way"]
    sig = [x for x in els if x["type"] == "node" and x.get("tags", {}).get("highway") == "traffic_signals"]
    print("%s: %d elements, %d ways, %d signals" % (out, len(els), len(ways), len(sig)), file=sys.stderr)


if __name__ == "__main__":
    main()
