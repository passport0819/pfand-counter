"""
Deposit jars for dairy: yogurt, cream, milk in a reusable glass carry 0.15 euro (a reusable jar many
dairies share). Word for word the same rule as
src/app/pfandcounter/DairyJarCheck.java; tools/DairyJarTest.java checks they agree.

A jar counts only when Open Food Facts says all three: a dairy category, glass, and a reusable
or deposit word. Glass alone is not enough — some yogurts come in a single-use glass
("einwegglas") without deposit — and plastic cups never qualify, whatever their labels say.

Used by tools/dairy-jar-measure.py.
"""
import re

DAIRY = {"yogurt", "yogurts", "yoghurt", "yoghurts", "joghurt", "joghurts", "dairies", "dairy",
         "quark", "quarks", "skyr", "cream", "creams", "sahne", "milk", "milks", "milch", "kefir",
         "kefirs", "buttermilk", "buttermilks"}
# A dairy word inside one of these is someone else's ingredient ("milk-chocolates").
NOT_DAIRY = {"chocolate", "chocolates", "spread", "spreads", "sauce", "sauces", "dressing",
             "dressings", "cheese", "cheeses", "ice", "ices", "coffee", "coffees"}
GLASS = re.compile(r"(^|[^a-z])(glass|glas|verre|vetro)")
REUSABLE = re.compile(r"mehrweg|pfand|returnable|reusable|leihglas|(^|[^a-z])mwg([^a-z]|$)")
SINGLE_USE = re.compile(r"einweg|non-returnable|non-reusable|single-use|pfandfrei|"
                        r"kein(-| )pfand|ohne(-| )pfand|no-deposit")


def words(tag):
    return [w for w in re.split(r"[^a-zäöüß]+", tag.lower()) if w]


def is_dairy(categories):
    dairy = False
    for tag in categories:
        ws = words(tag.split(":", 1)[-1])
        if any(w in NOT_DAIRY for w in ws):
            continue
        if any(w in DAIRY for w in ws):
            dairy = True
    return dairy


def is_deposit_jar(name, categories, packaging, labels):
    """name: product name (may be empty); categories, packaging, labels: tag lists."""
    if not is_dairy(categories):
        return False
    pack = " ".join(p.lower() for p in packaging)
    if not GLASS.search(pack):
        return False
    hints = pack + " " + " ".join(l.lower() for l in labels) + " " + (name or "").lower()
    if SINGLE_USE.search(hints):
        return False
    return bool(REUSABLE.search(hints))
