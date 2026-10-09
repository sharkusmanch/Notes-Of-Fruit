#!/usr/bin/env python3
"""Inspect a note body (`TextDataEncrypted`) outside the app. Standard library only.

    notedump.py extract <logcat.txt> <out.bin>   pull the last DUMP_BY_TITLE body out of a logcat capture
    notedump.py graph <body.bin>                 substring-graph statistics
    notedump.py runs <body.bin>                  stored text, one attribute run per line

<body.bin> is the gzip/zlib-compressed protobuf exactly as iCloud stores it (base64-decoded).
A capture comes from a debug build:

    adb shell am broadcast -p io.github.sharkusmanch.notesoffruit \
        -a com.example.applenotes.DUMP_BY_TITLE --es title '<part of the title>'
    adb logcat -d -v time -s AppleNotesDebug:* > logcat.txt

The dump only completes while the app is in the foreground. Bodies are private note
content: keep them out of the repository.
"""
import base64, collections, gzip, re, sys, zlib


def varint(b, i):
    r = s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7F) << s; s += 7
        if not c & 0x80:
            return r, i


def decode(b):
    """Protobuf wire format -> [(field number, wire type, value)], in source order."""
    i, out = 0, []
    while i < len(b):
        k, i = varint(b, i); fn, wt = k >> 3, k & 7
        if wt == 0:
            v, i = varint(b, i)
        elif wt == 2:
            n, i = varint(b, i); v = b[i:i + n]; i += n
        elif wt == 1:
            v = b[i:i + 8]; i += 8
        elif wt == 5:
            v = b[i:i + 4]; i += 4
        else:
            raise ValueError(f"wire type {wt}")
        out.append((fn, wt, v))
    return out


def load(path):
    raw = open(path, "rb").read()
    if raw[:2] == b"\x1f\x8b":
        return gzip.decompress(raw)
    if raw[:1] == b"\x78":
        return zlib.decompress(raw)
    return raw


def note_fields(raw):
    doc = next(v for f, w, v in decode(raw) if f == 2 and w == 2)
    return decode(next(v for f, w, v in decode(doc) if f == 3 and w == 2))


def substrings(nf):
    out = []
    for f, w, v in nf:
        if f != 3 or w != 2:
            continue
        d = decode(v)
        cid = next((decode(x) for ff, ww, x in d if ff == 1 and ww == 2), [])
        ts = next((decode(x) for ff, ww, x in d if ff == 3 and ww == 2), [])
        g = lambda fields, n: next((x for ff, ww, x in fields if ff == n and ww == 0), 0)
        out.append(dict(rep=g(cid, 1), clk=g(cid, 2), len=g(d, 2), tomb=bool(g(d, 4)),
                        tsr=g(ts, 1), tsc=g(ts, 2), ch=[x for ff, ww, x in d if ff == 5 and ww == 0]))
    return out


def cmd_extract(logcat, out):
    lines = open(logcat, errors="replace").read().splitlines()
    begin = [i for i, line in enumerate(lines) if "DUMP_B64_BEGIN" in line][-1]
    parts = []
    for line in lines[begin + 1:]:
        m = re.search(r"DUMP_B64\[\d+/\d+\] (\S+)", line)
        if m:
            parts.append(m.group(1))
        elif "DUMP_B64_END" in line:
            break
    body = base64.b64decode("".join(parts))
    open(out, "wb").write(body)
    print("wrote", len(body), "bytes, magic", body[:2].hex())


def cmd_graph(path):
    nf = note_fields(load(path))
    text = next(v for f, w, v in nf if f == 2 and w == 2).decode("utf-8")
    subs = substrings(nf)
    print("stored text (utf-16 units):", len(text.encode("utf-16-le")) // 2)
    print("substrings:", len(subs), "| tombstoned:", sum(s["tomb"] for s in subs),
          "| live length:", sum(s["len"] for s in subs if not s["tomb"]))
    print("children per substring:", dict(sorted(collections.Counter(len(s["ch"]) for s in subs).items())))
    # The walk the app does today: follow child[0] from the first substring.
    cur, seen = 0, []
    while cur < len(subs) and cur not in seen:
        seen.append(cur)
        if not subs[cur]["ch"]:
            break
        cur = subs[cur]["ch"][0]
    missed = [i for i in range(len(subs)) if i not in seen and not subs[i]["tomb"] and subs[i]["len"]]
    print("child[0] walk visits", len(seen), "of", len(subs),
          "| live characters it reads:", sum(subs[i]["len"] for i in seen if not subs[i]["tomb"]),
          "| live substrings it misses:", len(missed), f"({sum(subs[i]['len'] for i in missed)} characters)")
    runs = [decode(v) for f, w, v in nf if f == 5 and w == 2]
    print("attribute runs:", len(runs), "| total length:", sum(next((x for ff, _, x in r if ff == 1), 0) for r in runs))
    clock = next(v for f, w, v in nf if f == 4 and w == 2)
    print("replicas:", sum(1 for f, w, v in decode(clock) if w == 2))


def cmd_runs(path):
    nf = note_fields(load(path))
    text = next(v for f, w, v in nf if f == 2 and w == 2).decode("utf-8")
    pos = 0
    for f, w, v in nf:
        if f != 5 or w != 2:
            continue
        run = decode(v)
        n = next((x for ff, _, x in run if ff == 1), 0)
        style = next((decode(x) for ff, ww, x in run if ff == 2 and ww == 2), [])
        kind = next((x for ff, _, x in style if ff == 1), None)
        todo = next((decode(x) for ff, ww, x in style if ff == 5 and ww == 2), None)
        done = next((x for ff, _, x in todo if ff == 2), 0) if todo else None
        uid = next((x for ff, _, x in todo if ff == 1), b"").hex()[:6] if todo else ""
        print(f"[{pos:4}+{n:3}] style={kind} done={done} {uid:6} run={sorted({ff for ff, _, _ in run})} "
              f"para={sorted({ff for ff, _, _ in style})} {text[pos:pos + n]!r}")
        pos += n


if __name__ == "__main__":
    commands = {"extract": cmd_extract, "graph": cmd_graph, "runs": cmd_runs}
    if len(sys.argv) < 3 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    commands[sys.argv[1]](*sys.argv[2:])
