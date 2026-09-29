"""
The syrup rule, word for word as in src/app/pfandcounter/SyrupCheck.java (tools/SyrupTest.java
checks that both agree). Used by tools/seed-build.py to keep syrups out of the list.

A drink syrup or concentrate is not ready to drink, so no deposit is charged on its bottle
(§ 46 VerpackDG covers containers "filled with drinks"; Forum PET / IK e.V. lists
"Sirupflaschen" among the containers without deposit).
"""
import re

TAGS = {"en:syrups", "en:sirups", "de:sirup", "de:getränkesirup", "de:getränke-sirup",
        "fr:boissons-concentrees", "en:concentrates", "en:cordial", "en:cordials",
        "de:bierkonzentrat"}
# Not "-juice-concentrates": a bottled water carries that tag by mistake.
# Not "en:squash": in Open Food Facts that is mostly pumpkin.
TAG_ENDINGS = ("-syrups", "-sirup", "-sirups", "-to-be-diluted")
# A syrup category is sometimes wrong (25.09.2026: a cola, a mineral water and a cola-orange mix,
# all deposit drinks, were tagged en:syrups). So a category alone
# does not count when another category or the name points to a ready drink.
VETO_TAGS = {"en:sodas", "en:colas", "en:carbonated-drinks", "en:waters", "en:mineral-waters",
             "en:spring-waters", "en:beers", "en:iced-teas", "en:energy-drinks"}
VETO_TAG_ENDINGS = ("-sodas", "-colas", "-waters", "-beers")
VETO_NAME = re.compile(r"wasser|water|cola|bier|beer|schorle|radler|limo")
# The name decides on its own: nobody calls a ready drink "Sirup". None of 110 German products
# named SodaStream is a deposit drink; a ginger ale concentrate is known by name and brand alone,
# without a category (Open Food Facts, 25.09.2026).
NAME = re.compile(r"sirup|syrup|sirop|sciroppo|getränkekonzentrat|bierkonzentrat|dicksaft|sodastream")


def reason(name, tags):
    """What makes it a syrup, or None."""
    low = (name or "").lower()
    m = NAME.search(low)
    if m:
        return "name: " + m.group(0)
    tags = tags or []
    hit = next((t for t in tags if t in TAGS or t.endswith(TAG_ENDINGS)), None)
    if hit is None:
        return None
    if any(t in VETO_TAGS or t.endswith(VETO_TAG_ENDINGS) for t in tags) or VETO_NAME.search(low):
        return None
    return "category " + hit


def is_syrup(name, tags):
    return reason(name, tags) is not None
