"""Find where the two roads of each camera junction meet, using OpenStreetMap (Overpass).

Run by GitHub Actions; results are pushed to the lookup-results branch.
"""
import json, math, sys, time, urllib.parse, urllib.request

HOSTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
BOX = "(-17.95,30.95,-17.70,31.20)"

# name, road A regex, road B regex, rough position (used to pick between several crossings)
JUNCTIONS = [
    ("Jason Moyo Ave & Simon Muzenda St", "Jason Moyo", "Muzenda|Fourth Street|4th Street", -17.8302, 31.0545),
    ("Samora Machel Ave & Simon Muzenda St", "Samora Machel", "Muzenda|Fourth Street|4th Street", -17.8262, 31.0545),
    ("Julius Nyerere Way & Jason Moyo Ave", "Julius Nyerere", "Jason Moyo", -17.8302, 31.0471),
    ("Leopold Takawira St & Herbert Chitepo Ave", "Leopold Takawira", "Herbert Chitepo", -17.8235, 31.0428),
    ("Sam Nujoma St & Josiah Tongogara Ave", "Sam Nujoma|Second Street", "Josiah Tongogara", -17.8192, 31.0510),
    ("Churchill Ave & Sam Nujoma St", "Churchill", "Sam Nujoma|Second Street", -17.7915, 31.0462),
    ("Samora Machel Ave & Bishop Gaul Ave", "Samora Machel", "Bishop Gaul", -17.8258, 31.0235),
    ("Samora Machel Ave & Glenara Ave", "Samora Machel", "Glenara", -17.8245, 31.0855),
    ("Glenara Ave & Chiremba Rd", "Glenara", "Chiremba", -17.8485, 31.0815),
    ("Robert Mugabe Rd & Abdel Gamal Nasser Rd", "Robert Mugabe", "Abdel Gamal Nasser|Rotten Row", -17.8322, 31.0385),
    ("Cripps Rd & Remembrance Dr", "Cripps", "Remembrance", -17.8498, 31.0412),
    ("Seke Rd & Cripps Rd", "^Seke R", "Cripps", -17.8480, 31.0535),
    ("Samora Machel Ave & Abdel Gamal Nasser Rd", "Samora Machel", "Abdel Gamal Nasser|Rotten Row", -17.8289, 31.0404),
    ("Jason Moyo Ave & Abdel Gamal Nasser Rd", "Jason Moyo", "Abdel Gamal Nasser|Rotten Row", -17.8300, 31.0388),
]


def dist(a_lat, a_lon, b_lat, b_lon):
    r = math.pi / 180
    x = math.sin((b_lat - a_lat) * r / 2) ** 2 + math.cos(a_lat * r) * math.cos(b_lat * r) * math.sin((b_lon - a_lon) * r / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(x))


def ask(query):
    last = None
    for attempt in range(6):
        host = HOSTS[attempt % len(HOSTS)]
        try:
            req = urllib.request.Request(host, data=("data=" + urllib.parse.quote(query)).encode(),
                                         headers={"Content-Type": "application/x-www-form-urlencoded", "User-Agent": "RoboWatch junction lookup"})
            with urllib.request.urlopen(req, timeout=90) as resp:
                return json.loads(resp.read().decode())
        except Exception as e:  # busy server, timeout, bad reply
            last = "%s: %s" % (host, e)
            print("  retry after", last, flush=True)
            time.sleep(6)
    raise RuntimeError(last)


def way(regex):
    return 'way[highway][name~"%s",i]%s' % (regex, BOX)


def pick(nodes, lat, lon):
    near = min(nodes, key=lambda n: dist(lat, lon, n["lat"], n["lon"]))
    group = [n for n in nodes if dist(near["lat"], near["lon"], n["lat"], n["lon"]) < 150]
    return sum(n["lat"] for n in group) / len(group), sum(n["lon"] for n in group) / len(group), len(nodes), len(group)


results = []
for name, a, b, lat, lon in JUNCTIONS:
    print(name, flush=True)
    entry = {"n": name}
    try:
        reply = ask("[out:json][timeout:60];%s->.a;%s->.b;node(w.a)(w.b);out;" % (way(a), way(b)))
        nodes = [e for e in reply.get("elements", []) if e.get("type") == "node"]
        if nodes:
            p_lat, p_lon, total, used = pick(nodes, lat, lon)
            entry.update(lat=round(p_lat, 6), lon=round(p_lon, 6), how="shared point", crossings=total, used=used,
                         moved_m=round(dist(lat, lon, p_lat, p_lon)))
        else:
            # The roads never share a point on the map: find where they come closest.
            pts = {}
            for key, regex in (("a", a), ("b", b)):
                r = ask("[out:json][timeout:60];%s;out geom;" % way(regex))
                pts[key] = [(g["lat"], g["lon"]) for e in r.get("elements", []) for g in e.get("geometry", [])]
                entry["names_" + key] = sorted({e.get("tags", {}).get("name", "") for e in r.get("elements", [])})
                time.sleep(2)
            best = None
            for pa in pts["a"]:
                for pb in pts["b"]:
                    d = dist(pa[0], pa[1], pb[0], pb[1])
                    if best is None or d < best[0]:
                        best = (d, pa, pb)
            if best:
                entry.update(lat=round((best[1][0] + best[2][0]) / 2, 6), lon=round((best[1][1] + best[2][1]) / 2, 6),
                             how="closest approach", gap_m=round(best[0]),
                             moved_m=round(dist(lat, lon, (best[1][0] + best[2][0]) / 2, (best[1][1] + best[2][1]) / 2)))
            else:
                entry["how"] = "not found"
    except Exception as e:
        entry["how"] = "error"
        entry["error"] = str(e)
    print("  ", entry, flush=True)
    results.append(entry)
    time.sleep(2)

# Also check that the app's all-in-one request is accepted by the server.
combined = "[out:json][timeout:60];" + "".join(
    "%s->.a;%s->.b;node(w.a)(w.b);out;make sep k=%d;out;" % (way(a), way(b), k) for k, (_, a, b, _, _) in enumerate(JUNCTIONS[:3]))
try:
    reply = ask(combined)
    kinds = [e.get("type") for e in reply.get("elements", [])]
    combined_result = {"ok": True, "types": kinds}
except Exception as e:
    combined_result = {"ok": False, "error": str(e)}

json.dump({"junctions": results, "combined_query": combined_result}, open("lookup.json", "w"), indent=1)
print(json.dumps(combined_result))
