import csv, re, sys
src_dir, curated_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

SUFFIXES = [
    r"co\.?,?\s*ltd\.?", r"company limited", r"limited", r"ltd\.?", r"inc\.?", r"incorporated", r"corporation", r"corp\.?",
    r"l\.?l\.?c\.?", r"gmbh", r"ag", r"s\.?a\.?", r"s\.?p\.?a\.?", r"b\.?v\.?", r"n\.?v\.?", r"pty\.?", r"plc", r"kg",
    r"s\.?r\.?l\.?", r"s\.?r\.?o\.?", r"a/s", r"ab", r"oy", r"as", r"sdn\.? bhd\.?", r"pvt\.?", r"private", r"co\.?",
    r"company", r"k\.?k\.?", r"ooo", r"zao", r"llp", r"lp",
]
suffix_re = re.compile(r"[\s,.\-]*\b(?:" + "|".join(SUFFIXES) + r")\s*$", re.I)

def normalize(name):
    name = name.strip().strip('"').strip()
    name = re.sub(r"\s+", " ", name)
    # Strip legal suffixes repeatedly ("Foo Technology Co., Ltd." -> "Foo Technology").
    for _ in range(4):
        new = suffix_re.sub("", name).rstrip(" ,.-")
        if new == name or len(new) < 2:
            break
        name = new
    # All-caps names read badly; title-case words longer than three letters.
    letters = [c for c in name if c.isalpha()]
    if letters and all(c.isupper() for c in letters) and len(letters) > 4:
        words = []
        for w in name.split(" "):
            core = re.sub(r"[^A-Za-z]", "", w)
            words.append(w if len(core) <= 3 else w.title())
        name = " ".join(words)
    return name.strip()

curated = {}
for line in open(curated_path, encoding="utf-8"):
    line = line.rstrip("\n")
    if "\t" in line:
        k, v = line.split("\t", 1)
        curated[k.upper()] = v

def key_for(registry, assignment):
    a = assignment.upper()
    if registry == "MA-L" and len(a) == 6:
        return f"{a[0:2]}:{a[2:4]}:{a[4:6]}"
    if registry == "MA-M" and len(a) == 7:
        return f"{a[0:2]}:{a[2:4]}:{a[4:6]}:{a[6]}"
    if registry == "MA-S" and len(a) == 9:
        return f"{a[0:2]}:{a[2:4]}:{a[4:6]}:{a[6:8]}:{a[8]}"
    return None

entries = {}
for fname in ["oui.csv", "mam.csv", "oui36.csv"]:
    with open(f"{src_dir}/{fname}", encoding="utf-8", errors="replace", newline="") as f:
        reader = csv.reader(f)
        next(reader, None)
        for row in reader:
            if len(row) < 3: continue
            key = key_for(row[0], row[1])
            if not key: continue
            name = normalize(row[2])
            if name.lower() in ("private", "ieee registration authority", ""):
                continue
            entries[key] = name

ALIASES = {
    "Intel Corporate": "Intel", "zte": "ZTE", "Huawei Technologies": "Huawei", "Huawei Device": "Huawei",
    "Xiaomi Communications": "Xiaomi", "Cisco Systems": "Cisco", "Samsung Electronics": "Samsung",
    "Amazon Technologies": "Amazon", "Microsoft Corporation": "Microsoft", "Espressif": "Espressif",
    "Sony Interactive Entertainment": "Sony", "Beijing Xiaomi Mobile Software": "Xiaomi",
    "Beijing Xiaomi Electronics": "Xiaomi", "Guangdong Oppo Mobile Telecommunications": "OPPO",
    "vivo Mobile Communication": "vivo", "Nintendo": "Nintendo", "Hon Hai Precision Ind.": "Foxconn",
    "Hon Hai Precision Industry": "Foxconn", "Motorola Mobility, a Lenovo Company": "Motorola",
    "LG Electronics (Mobile Communications)": "LG", "LG Electronics": "LG", "LG Innotek": "LG",
    "Hewlett Packard": "HP", "Hewlett Packard Enterprise": "HPE", "HP Inc": "HP",
    "Dell Technologies": "Dell", "Texas Instruments": "Texas Instruments", "Raspberry Pi Trading": "Raspberry Pi",
    "Raspberry Pi Foundation": "Raspberry Pi", "Ubiquiti Networks": "Ubiquiti", "Ubiquiti": "Ubiquiti",
    "Netgear": "Netgear", "D-Link International": "D-Link", "D-Link": "D-Link", "ASUSTek COMPUTER": "ASUS",
    "ASUSTek Computer": "ASUS", "Sonos": "Sonos", "Roku": "Roku", "Nest Labs": "Google Nest",
}
for k, v in list(entries.items()):
    if v in ALIASES:
        entries[k] = ALIASES[v]
    elif v.lower().startswith("tp-link"):
        entries[k] = "TP-Link"
    elif v.lower().startswith("samsung electro"):
        entries[k] = "Samsung"
    elif v.lower().startswith("huawei "):
        entries[k] = "Huawei"
    elif v.lower().startswith("xiaomi "):
        entries[k] = "Xiaomi"
    elif v.lower().startswith("apple"):
        entries[k] = "Apple"
    elif "hikvision" in v.lower():
        entries[k] = "Hikvision"
    elif v.lower().startswith("zhejiang dahua"):
        entries[k] = "Dahua"
    elif v.lower().startswith("reolink"):
        entries[k] = "Reolink"
    elif v.lower().startswith("tuya"):
        entries[k] = "Tuya"
    elif v.lower().startswith("shelly"):
        entries[k] = "Shelly"

# Curated spellings win; curated keys the registry no longer lists stay.
entries.update(curated)
with open(out_path, "w", encoding="utf-8") as out:
    for k in sorted(entries, key=lambda k: (len(k), k)):
        out.write(f"{k}\t{entries[k]}\n")
print(len(entries), "entries")
