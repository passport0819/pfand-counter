#!/usr/bin/env python3
"""
Turns the harvested Open Food Facts records into res/raw/deposits.tsv — the list the app
ships with, so it asks fewer questions on day one.

Only two classes are written, and only where the answer is defensible:

  25 cents  every beverage CAN sold in Germany. Single-use deposit is law
            (VerpackDG § 46, formerly VerpackG § 31): 0.25 EUR on single-use drink
            containers from 0.1 to 3 litres. Cans are never reusable, so this needs no guessing.
   8 cents  GLASS BOTTLED BEER with a German article number (EAN 400-440).
            Not law but the pooled-bottle custom (GDB): 0.08 EUR up to 0.5 l.
            Known exception: swing-top bottles cost 0.15 EUR — brands listed in
            SWING_TOP below are therefore skipped rather than shipped wrong.

Everything else (PET, glass water/soft drinks, juice, imports) stays out: from the
packaging data alone it cannot be told apart, and a wrong number in the list is worse
than one question to the user.
"""
import glob, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import syrup_rule  # noqa: E402
import dairy_jar_rule  # noqa: E402

# Dieselbe Standarddatei wie in seed-from-parquet.sh. Vorher stand hier
# off-raw.jsonl: die Kette las stillschweigend einen alten Restbestand von 600 Sätzen
# statt der 38015 frisch abgezogenen. Gemessen am 22.09.2026.
RAW = os.environ.get("HARVEST_OUT", os.path.join(os.environ.get("TMPDIR", "/tmp"), "off-de-beverages.jsonl"))
# Dairy candidates from the same script (reusable yogurt, cream and milk jars, 0.15).
DAIRY_RAW = os.environ.get("HARVEST_DAIRY_OUT", os.path.join(os.environ.get("TMPDIR", "/tmp"), "off-de-dairy.jsonl"))
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "res", "raw", "deposits.tsv")

CAN_SHAPES = {"en:can", "en:drink-can", "en:beverage-can", "en:canned", "en:drinks-can"}
CAN_MATERIALS = {"en:aluminium", "en:aluminum", "en:steel", "en:metal"}
GLASS = {"en:glass", "en:clear-glass", "en:brown-glass", "en:green-glass", "en:dark-glass"}
BOTTLE_SHAPES = {"en:bottle", "en:glass-bottle"}
# Parts that sit ON a container and say nothing about its shape. Found 22.09.2026: a beer that
# lists glass plus a "bottle-cap" and nothing else made the old check read the cap as the
# container's shape, call it "not a glass bottle" and ship it at 0.15.
CLOSURES = {"en:lid", "en:label", "en:bottle-cap", "en:lid-or-cap", "en:seal", "en:sleeve",
            "en:capsule", "en:unknown", "de:Schraubverschluss", "de:Kronkorken", "de:Banderole"}
# Beer for the 0.08 rule. Non-alcoholic beer and Radler travel in the same pooled bottles;
# found 22.09.2026 via an alcohol-free wheat beer and a Radler shipped at 0.15. Ginger beer is not
# beer, which is why this is a fixed set and not a substring match.
BEER_CATEGORIES = {"en:beers", "en:non-alcoholic-beers", "en:beer-based-alcoholic-beverages",
                   "en:radler-beers", "en:non-alcoholic-radler-beers"}
# No deposit because it is not a drink sold for drinking, or because the law exempts it.
# Measured 22.09.2026: without the cooking block, 78 tins of coconut milk and plant cream
# landed in the list as 0.25 EUR cans. German deposit law covers BEVERAGE packaging only.
# Milk drinks are NOT in here on purpose: their exemption ended on 01.01.2024, so a
# single-use plastic bottle or can of drinking milk does carry the 0.25 EUR (a carton still does not).
EXCLUDE_CATEGORIES = ("en:wines", "en:sparkling-wines", "en:spirits", "en:liqueurs",
                      "en:champagnes", "en:syrups", "en:concentrated",
                      "en:coconut-milks-and-creams", "en:coconut-milks",
                      "en:plant-based-creams", "en:plant-based-creams-for-cooking",
                      "en:soups", "en:dairy-desserts", "en:fermented-dairy-desserts",
                      "en:non-dairy-desserts", "en:non-dairy-yogurts",
                      "en:soy-milk-yogurts", "en:sauces", "en:condiments")
# Plastic for the large-bottle rule. Measured 22.09.2026 on every plastic bottle whose data
# states its deposit: above 1.0 l, 148 of 149 are single-use; 0.5-1.0 l, 41 % are reusable;
# up to 0.5 l, 12 %. So size settles it above one litre and nowhere else.
PLASTIC = {"en:pet-1-polyethylene-terephthalate", "en:plastic", "en:polyethylene"}
CARTONS = {"en:brick", "en:tetra-pak", "en:paperboard", "en:cardboard"}
# "kloster" was in here until 23.09.2026. Measured then: it changed exactly two entries, and
# both are ordinary 0.08 bottles per the shops' own deposit figures (see data/geprueft.tsv).
# A monastery name says nothing about the cap.
SWING_TOP = ("flensburger", "grolsch", "buegel", "bügel")

def gtin_ok(code):
    """The GS1 check digit adds up. A code that fails it is a typo in Open Food Facts or a short
    UPC-E read off an EAN-13 (29.09.2026: 11051204 for a 4105120... beer); either way no scan of
    a German bottle should match it, and a UPC-E phantom would count the wrong bottle."""
    n = len(code)
    total = sum((3 if (n - 1 - i) % 2 == 1 else 1) * int(code[i]) for i in range(n - 1))
    return (10 - total % 10) % 10 == int(code[-1])

def norm_code(code):
    code = (code or "").strip()
    if not code.isdigit():
        return None
    if len(code) == 12:          # UPC-A is read as a 13 digit EAN with a leading zero
        code = "0" + code
    return code if len(code) in (8, 13) and gtin_ok(code) else None

def litres(quantity):
    q = (quantity or "").lower().replace(",", ".")
    m = re.search(r"([0-9]*\.?[0-9]+)\s*(l|liter|litre|ml|cl)\b", q)
    if not m:
        return None
    value, unit = float(m.group(1)), m.group(2)
    if unit == "ml":
        value /= 1000.0
    elif unit == "cl":
        value /= 100.0
    return value

# What the source SAYS, as opposed to what we infer from shape and material. For 1325 German
# drinks, labels_tags / packaging_text / packaging_recycling_tags spell the answer out in
# German. A stated fact beats a derived one, so these override the shape rules below — and
# they beat a model answer too: measured 22.09.2026, they contradicted Jev in 18 cases,
# every one of them among those Jev itself had flagged as uncertain.
# "Pfandflasche" is NOT in here. It only says the bottle carries a deposit — single-use PET
# says it too ("1 PET-Pfandflasche. Rückgabe." on a juice and a milk drink). Found
# 22.09.2026: it turned those into 0.15 reusable bottles, in the list and in the controls.
SAYS_REUSABLE = re.compile(r"mehrweg|reusable", re.I)
# Found 22.09.2026: a swing-top beer said "Bügelverschluss" in its packaging, not in its name,
# and went out at 0.08 instead of 0.15.
SAYS_SWING_TOP = re.compile(r"b[uü]gel|swing.?top", re.I)
SAYS_SINGLE_USE = re.compile(r"einweg|single.use", re.I)
# Soft-drink categories for the small-bottle case in stated_deposit (not beer, not water or juice).
SOFT_DRINK = re.compile(r"soda|lemonade|limonade|cola|iced-tea|eistee|tea-based|malt|malz|fassbrause|"
                        r"energy|ginger|tonic|bitter-lemon|mate", re.I)
SAYS_NO_DEPOSIT = re.compile(r"pfandfrei|ohne pfand|deposit.free", re.I)
# A stand-up pouch or a tube bag carries no deposit (§ 46 VerpackDG: "Folien-Standbodenbeutel",
# "Getränke-Polyethylen-Schlauchbeutel"), even when its data says "Einweg". Found 27.09.2026: a
# pouch labelled "Einweg-Trinkpäckchen" went out at 0.25. Same words as the app's maker hint
# (tools/maker_rule.py, POUCH_WORDS) plus the plain ones.
SAYS_POUCH = re.compile(r"trinkp[äa]c?k|trinkpa(ck|ket)|saftp[äa]c?k|trinkt[üu]te|standbodenbeutel|"
                        r"stand.?up.?pouch|schlauchbeutel|pouch", re.I)

def flatten(value):
    """labels_tags is a list, packaging_text a string, packaging sometimes nested."""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return " ".join(flatten(v) for v in value)
    if isinstance(value, dict):
        return " ".join(flatten(v) for v in value.values())
    return str(value)

def stated_deposit(product, volume, categories):
    """
    The deposit the data states outright, or None when it says nothing — or two things at once.
    "Mehrweg" alone does not give the amount, so the customary split still applies: a German
    glass beer bottle up to 0.5 l is 0.08, everything else reusable is 0.15.
    """
    text = " ".join(flatten(product.get(k)) for k in
                    ("labels_tags", "packaging_text", "packaging_recycling_tags", "packaging"))
    if not text or SAYS_NO_DEPOSIT.search(text) or SAYS_POUCH.search(text):
        return None
    reusable, single = bool(SAYS_REUSABLE.search(text)), bool(SAYS_SINGLE_USE.search(text))
    if reusable == single:            # nothing said, or both said — not a statement
        return None
    if single:
        return 25
    # Found on 22.09.2026 by measuring a model against this very function: the stated path
    # skipped the swing-top check that the derived rules have, and shipped a swing-top beer
    # at 0.08. A stated "Mehrweg" settles THAT it is reusable, not for how much — a swing-top
    # bottle is 0.15, so those brands take the 0.15 branch instead of being dropped.
    if any(brand in name_of(product).lower() for brand in SWING_TOP) \
            or SAYS_SWING_TOP.search(text):
        return 15
    if is_beer(categories) and is_glass_bottle(product) and volume and volume <= 0.5:
        return 8
    # Reusable beer up to 0.5 l whose packaging entry names no glass: still the pooled 0.08 bottle.
    # Measured 28.09.2026 on stated-reusable beer without a glass entry: 18 of 20 are 0.08, and all
    # 20 went out at 0.15 before. Reusable beer in plastic does not occur there.
    if is_beer(categories) and volume and volume <= 0.5 and not (parts(product)[1] & PLASTIC):
        return 8
    # Reusable and above 1 l, but no glass named: measured 28.09.2026, 1 of 10 such products is really
    # 0.15 — most are single-use PET whose entry says "Mehrweg" by mistake. Not a statement to trust.
    if volume and volume > 1.0 and not is_glass_bottle(product):
        return None
    # Small reusable soft drinks (colas, lemonades, iced tea, malt drinks) without a glass entry: the
    # same pooled 0.08 / 0.15 split as the small glass bottles below. Measured 28.09.2026: 6 of 9 are 0.08.
    if volume and volume <= 0.33 and SOFT_DRINK.search(" ".join(categories)):
        return None
    # Small reusable glass says "Mehrweg" but not how much. Measured 22.09.2026 on bottles with
    # a known deposit: up to 0.33 l, 25 of 32 are 0.08 (colas, lemonades and Kölsch in the pooled
    # bottle), not 0.15. The maintainer decided the same day: do not guess these any more — leave
    # them to the app's question. Above 0.5 l, 36 of 36 are 0.15.
    if is_glass_bottle(product) and volume and volume <= 0.33:
        return None
    return 15

# The label text (ingredients_text, read off the photos by OCR) states the deposit on 69 German
# drinks that are not in the list otherwise. Only ONE reading is safe, measured 23.09.2026:
# "Einweg" together with the amount 0,25 printed next to it. "Mehrweg" alone is not — a 0.275 l
# lager says "Mehrwegflasche", the general rule would make it 0.15, and its deposit is 0.08.
# The maintainer decided the same day: take the 0.25 statements only.
LABEL_SINGLE_USE_25 = re.compile(r"einweg\w*.{0,30}?0[,.]25|0[,.]25.{0,30}?einweg", re.I | re.S)

def label_says_25(product):
    text = flatten(product.get("ingredients_text"))
    return bool(LABEL_SINGLE_USE_25.search(text)) and not SAYS_REUSABLE.search(text)

def parts(product):
    """(shapes, materials) from the structured packagings, with the flat tags as fallback."""
    shapes, materials = set(), set()
    for part in product.get("packagings") or []:
        if part.get("shape"): shapes.add(part["shape"])
        if part.get("material"): materials.add(part["material"])
    if not shapes and not materials:
        for tag in product.get("packaging_tags") or []:
            (shapes if tag in CAN_SHAPES or tag in BOTTLE_SHAPES else materials).add(tag)
    return shapes, materials

def is_can(product):
    shapes, materials = parts(product)
    if shapes & CAN_SHAPES:
        return True
    # A can without a shape entry: metal in food contact and no bottle anywhere.
    if shapes & BOTTLE_SHAPES or shapes & GLASS:
        return False
    # Glass plus metal and no shape is a bottle with its crown cap, not a can. Measured 28.09.2026:
    # 4 beers of 0.33-0.5 l went out at 0.25 this way; the shop refunds 0.08 for all four.
    if materials & GLASS:
        return False
    for part in product.get("packagings") or []:
        if part.get("material") in CAN_MATERIALS and part.get("food_contact"):
            return True
    return not shapes and bool(materials & CAN_MATERIALS)

def is_large_plastic_bottle(product, volume):
    """A plastic bottle above 1.0 l: single-use in 148 of 149 stated cases (see PLASTIC)."""
    if not volume or volume <= 1.0:
        return False
    shapes, materials = parts(product)
    everything = shapes | materials
    if not (materials & PLASTIC) or (materials & GLASS) or (everything & CARTONS):
        return False
    return not (shapes - CLOSURES) or bool(shapes & BOTTLE_SHAPES)

def is_beer(categories):
    return bool(BEER_CATEGORIES & set(categories or []))

def is_glass_bottle(product):
    shapes, materials = parts(product)
    shapes = shapes - CLOSURES
    if shapes & CAN_SHAPES:
        return False
    return bool(materials & GLASS) and (not shapes or bool(shapes & BOTTLE_SHAPES))

def text_of(field):
    """The Parquet export carries names as a list of {lang, text}; the CSV export as plain text."""
    if isinstance(field, str):
        return field.strip()
    if isinstance(field, list):
        by_lang = {}
        for entry in field:
            if isinstance(entry, dict) and entry.get("text"):
                by_lang[entry.get("lang") or ""] = entry["text"].strip()
        for lang in ("main", "de", "en"):
            if by_lang.get(lang):
                return by_lang[lang]
        return next(iter(by_lang.values()), "")
    return ""

def name_of(product):
    brand = text_of(product.get("brands")).split(",")[0].strip()
    name = text_of(product.get("product_name"))
    if brand and brand.lower() not in name.lower():
        name = (brand + " " + name).strip()
    name = re.sub(r"\s+", " ", name)
    return name[:46]

def pattern_key(code, product, volume):
    """Maker, material and size: the first 7 digits of a 13-digit code name the company."""
    if not code or len(code) != 13 or not volume:
        return None
    shapes, materials = parts(product)
    material = "glass" if materials & GLASS else "plastic" if materials & PLASTIC else None
    return (code[:7], material, round(volume, 2)) if material else None

def learn_patterns():
    """
    What each maker states for a material and size. Used only where at least PATTERN_MIN of
    its products agree and none disagrees. Measured 22.09.2026 by leaving each stated product
    out and predicting it from the others: 249 of 254 right (98.0 %); it reaches 242 of the
    products the other rules leave open. The maintainer decided the same day to use it.
    """
    seen = {}
    for line in open(RAW, encoding="utf-8"):
        try:
            p = json.loads(line)
        except Exception:
            continue
        volume = litres(p.get("quantity"))
        cats = p.get("categories_tags") or []
        if volume is None or not (0.1 <= volume <= 3.0) or any(c in cats for c in EXCLUDE_CATEGORIES):
            continue
        stated = stated_deposit(p, volume, cats)
        key = pattern_key(norm_code(p.get("code")), p, volume)
        if stated is not None and key:
            seen.setdefault(key, {}).setdefault(stated, 0)
            seen[key][stated] += 1
    return {k: next(iter(v)) for k, v in seen.items() if len(v) == 1 and sum(v.values()) >= PATTERN_MIN}

PATTERN_MIN = 3

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ODBL_HEAD = (
    "# barcodes and product names: (c) Open Food Facts contributors, Open Database License (ODbL) 1.0, "
    "https://opendatacommons.org/licenses/odbl/1-0/",
    "# this list as a whole is made available under the ODbL 1.0, separately from the app's GPL-3.0 code",
)

def hand_files():
    """Every data/geprueft*.tsv, in name order — a later file wins over an earlier one."""
    return sorted(glob.glob(os.path.join(ROOT, "data", "geprueft*.tsv")))

def hand_rows():
    """{code: (cents, name)} from the hand-checked files (code<TAB>cents<TAB>name<TAB>evidence)."""
    rows = {}
    for path in hand_files():
        for line in open(path, encoding="utf-8"):
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            try:
                rows[parts[0]] = (int(parts[1]), parts[2])
            except ValueError:
                continue
    return rows

def hand_asks():
    """Codes a hand-checked file marks "?" (cents column): the maintainer decided the app asks for
    these, whatever a rule or a shop says. Kept apart from hand_rows(), which holds amounts only."""
    asks = set()
    for path in hand_files():
        for line in open(path, encoding="utf-8"):
            parts = line.rstrip("\n").split("\t")
            if not line.startswith("#") and len(parts) >= 2 and parts[1] == "?":
                asks.add(parts[0])
    return asks

def dairy_jars():
    """{code: (15, name)} for every dairy jar Open Food Facts marks as reusable glass — the rule the
    app applies to a single barcode (DairyJarCheck), here over the whole export. In the list they
    also give unknown jars of the same maker their 0.15 as a hint (tools/maker_rule.py).
    Measured 27.09.2026: 102 jars in the German export."""
    out = {}
    for line in open(DAIRY_RAW, encoding="utf-8"):
        r = json.loads(line)
        packaging = [x for x in (r.get("mats") or []) + (r.get("shapes") or []) + (r.get("recs") or [])
                     + (r.get("packaging_tags") or []) if x]
        names = [n for n in (r.get("names") or []) if n]
        if not dairy_jar_rule.is_deposit_jar(names[0] if names else "", r.get("categories_tags") or [],
                                             packaging, r.get("labels_tags") or []):
            continue
        code, name = norm_code(r.get("code")), name_of(r)
        if code and name:
            out[code] = (15, name)
    return out

def main():
    if not os.path.exists(RAW):
        sys.exit(f"keine Rohdaten unter {RAW} — erst tools/seed-from-parquet.sh laufen lassen")
    if not os.path.exists(DAIRY_RAW):
        sys.exit(f"keine Milchprodukte unter {DAIRY_RAW} — erst tools/seed-from-parquet.sh laufen lassen")
    patterns = learn_patterns()

    rows, stats = {}, {"gelesen": 0, "dose": 0, "bierglas": 0, "uebersprungen": 0}
    reasons = {}
    for line in open(RAW, encoding="utf-8"):
        try:
            p = json.loads(line)
        except Exception:
            continue
        stats["gelesen"] += 1
        code = norm_code(p.get("code"))
        cats = p.get("categories_tags") or []
        volume = litres(p.get("quantity"))
        name = name_of(p)

        def skip(reason):
            stats["uebersprungen"] += 1
            reasons[reason] = reasons.get(reason, 0) + 1

        if not code:
            skip("Barcode unbrauchbar"); continue
        if not name:
            skip("kein Name"); continue
        if any(c in cats for c in EXCLUDE_CATEGORIES):
            skip("kein Getränk oder pfandfrei (Wein, Spirituose, Kochzutat)"); continue
        if volume is None or not (0.1 <= volume <= 3.0):
            skip("Füllmenge fehlt oder außerhalb 0,1–3 l"); continue

        stated = stated_deposit(p, volume, cats)
        if stated is not None:
            rows[code] = (stated, name)
            stats["laut Angabe"] = stats.get("laut Angabe", 0) + 1
        elif label_says_25(p):
            rows[code] = (25, name)
            stats["laut Etikett"] = stats.get("laut Etikett", 0) + 1
        elif is_can(p):
            rows[code] = (25, name); stats["dose"] += 1
        elif is_beer(cats) and is_glass_bottle(p):
            if not code.startswith(("40", "41", "42", "43", "44")):
                skip("Bier im Glas, aber keine deutsche Artikelnummer"); continue
            if any(s in name.lower() for s in SWING_TOP):
                skip("Bügelflasche — 0,15 statt 0,08, nicht sicher genug"); continue
            if volume > 0.75:
                skip("Bierflasche größer als 0,75 l — Pfand uneinheitlich"); continue
            rows[code] = (8, name); stats["bierglas"] += 1
        elif is_large_plastic_bottle(p, volume):
            rows[code] = (25, name); stats["pet_gross"] = stats.get("pet_gross", 0) + 1
        elif pattern_key(code, p, volume) in patterns:
            rows[code] = (patterns[pattern_key(code, p, volume)], name)
            stats["muster"] = stats.get("muster", 0) + 1
        else:
            skip("Verpackung nicht eindeutig (PET, Glas ohne Bier, unbekannt)")

    jars = {c: v for c, v in dairy_jars().items() if c not in rows}
    rows.update(jars)

    # The hand-checked entries go in last and win. They are the only ones a person looked at
    # one by one, so neither a rule nor a model answer may quietly replace them.
    hand = hand_rows()
    rows.update(hand)
    von_hand = len(hand)

    # Syrups carry no deposit (see tools/syrup_rule.py). The rule "plastic bottle over 1.0 l =
    # 0.25" put a 1.5 l elderflower syrup in (25.09.2026). Hand-checked entries stay.
    sirup = [c for c, (cents, name) in rows.items() if c not in hand and syrup_rule.is_syrup(name, [])]
    for code in sirup:
        print(f"Sirup, kein Pfand:     {code} {rows[code][1]}")
        del rows[code]

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Pfand Counter — shipped deposit list, built by tools/seed-build.py\n")
        f.write("# code<TAB>cents<TAB>name   |  25 = single-use can (law), 8 = German glass beer (custom)\n")
        f.write("# 15 on dairy products: Open Food Facts states dairy, glass and reusable (tools/dairy_jar_rule.py)\n")
        # ODbL 4.4a/4.6: a public derived database stays under ODbL and names its source.
        for line in ODBL_HEAD:
            f.write(line + "\n")
        for code in sorted(rows):
            cents, name = rows[code]
            f.write(f"{code}\t{cents}\t{name}\n")

    print(f"Rohsätze gelesen:      {stats['gelesen']}")
    print(f"laut Quellenangabe:    {stats.get('laut Angabe', 0)}")
    print(f"laut Etikett (0,25):   {stats.get('laut Etikett', 0)}")
    print(f"Dosen  -> 0,25 EUR:    {stats['dose']}")
    print(f"Bierglas -> 0,08 EUR:  {stats['bierglas']}")
    print(f"PET über 1 l -> 0,25:  {stats.get('pet_gross', 0)}")
    print(f"Herstellermuster:      {stats.get('muster', 0)}  (aus {len(patterns)} einstimmigen Mustern)")
    print(f"Pfandgläser (Milch):   {len(jars)}")
    print(f"von Hand geprüft:      {von_hand}")
    print(f"Sirup entfernt:        {len(sirup)}")
    print(f"übersprungen:          {stats['uebersprungen']}")
    for reason, n in sorted(reasons.items(), key=lambda kv: -kv[1]):
        print(f"   {n:6d}  {reason}")
    print(f"geschrieben: {OUT} ({os.path.getsize(OUT)} Bytes, {len(rows)} Einträge)")

if __name__ == "__main__":
    main()
