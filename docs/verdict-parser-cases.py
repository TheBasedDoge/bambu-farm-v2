#!/usr/bin/env python3
"""Case table for OllamaService.parseVerdict / findContradiction.

There is no JDK in the agent sandbox, so this mirrors the Java closely enough to test the
verdict logic against real logged model replies. Run it after touching either method:

    python3 docs/verdict-parser-cases.py

Kept in the repo because this parser has been the source of three separate production bugs:
  - startsWith() only, so a 95%-confidence spaghetti detection recorded as OK and a print
    ran to completion detached;
  - a bare "Confidence: 95" with no verdict counting as BED CLEAR;
  - "Objects: none," (with a comma) reading as AN OBJECT WAS FOUND, blocking clear beds on
    every comma-separated reply.

If you change the Java, change this and keep it at 100%. Mirror drift makes it worthless.
"""
import re
FINDINGS = re.compile(r"(?is)\b(?:problems|objects)\s*:\s*\[?\s*(.*?)(?=\n|confidence\s*:|reason\s*:|clear\s*:|$)")
CONFIDENCE = re.compile(r"(?i)\bconfidence\s*:\s*\[?\s*(\d{1,3})")
CLEAR_FIELD = re.compile(r"(?i)\bclear\s*:\s*\[?\s*(yes|no)\b")
NO_FINDINGS = {"none","no","nothing","n/a","na","-","--","none.","none visible","none detected"}
RESPONSE_KEYWORDS = {"YES","NO","GOOD","POOR"}
SAFE_CONFIDENCE_MIN = 90

OBJECT_PHRASE = re.compile(r"(?i)\b(objects?\s+(?:on|upon)\s+the\s+(?:bed|plate)"
    r"|sitting\s+on\s+the\s+(?:bed|plate)|resting\s+on\s+the\s+(?:bed|plate)"
    r"|(?:left|leftover|remaining|still)\s+on\s+the\s+(?:bed|plate)"
    r"|not\s+empty|not\s+clear|is\s+occupied)\b")
ROUND_SHAPE = re.compile(r"(?i)\b(rings?|donuts?|doughnuts?"
    r"|circular\s+(?:shape|object|part|item)s?|round\s+(?:shape|object|part|item)s?"
    r"|cylinders?|cylindrical)\b")
NEGATION = re.compile(r"(?i)\b(no|not|none|nothing|never|without|absent|absence|free|lack|lacks|lacking"
    r"|cannot|can't|don't|doesn't|isn't|aren't|didn't|nor)\b|n't\b")
SEGMENT_SPLIT = re.compile(r"[\n.;!?]")
FIELD_LABEL = re.compile(r"(?i)\b(?:objects?|problems?|reason|confidence|clear|observations?|findings)\s*:")

def find_contradiction(text):
    for segment in SEGMENT_SPLIT.split(FIELD_LABEL.sub("\n", text)):
        for pattern in (OBJECT_PHRASE, ROUND_SHAPE):
            for m in pattern.finditer(segment):
                if not NEGATION.search(segment[:m.start()]):
                    return m.group(1)
    return None

def fg(p,t):
    m=p.search(t); return m.group(1) if m else None

def parse_verdict(text, kw):
    if not text or not text.strip(): return None,None
    c = fg(CONFIDENCE,text); conf = int(c) if c is not None else None
    fmp = "problems:" in text.lower()
    f = fg(FINDINGS,text)
    if f is not None: f = re.sub(r"[\s.,;:*_\]\[]+$","",f).strip()
    positive=None
    if f is not None:
        found = f.lower().strip() not in NO_FINDINGS and f.strip()!=""
        positive = (fmp==found)
    if positive is None:
        cl=fg(CLEAR_FIELD,text)
        if cl is not None: positive = cl.lower()=="yes"
    if positive is None:
        fw=re.sub(r"[^A-Za-z]","",text.strip().split()[0]).upper()
        if fw in RESPONSE_KEYWORDS: positive=(fw==kw.upper())
    bed_gate = (not fmp) and kw.strip().upper()!="GOOD"
    if positive is None and conf is not None and not bed_gate: positive = conf>=50
    if positive is None: return None,"unparseable"
    if not positive: return False,None
    if bed_gate and conf is not None and conf<SAFE_CONFIDENCE_MIN:
        return False,"confidence %d is below the %d needed to start a print"%(conf,SAFE_CONFIDENCE_MIN)
    if bed_gate:
        hit=find_contradiction(text)
        if hit: return False,'says clear but mentions "%s"'%hit
    return True,None

CASES=[
("REAL: cupholder explained away as plate feature","Objects: none, Confidence: 100, Reason: The circular shape is a gridded plate feature, not a 3D printed object.","YES",False),
("genuinely empty bed","Objects: none  Confidence: 100  Reason: The build plate is empty and clean.","YES",True),
("empty, rings mentioned but negated","Objects: none  Confidence: 95  Reason: There are no rings or leftover parts on the plate.","YES",True),
("empty, negated circular mention","Clear: YES  Confidence: 96  Reason: I see no circular shapes or objects on the bed.","YES",True),
("Objects: none but reason names a ring","Clear: YES  Confidence: 95  Objects: none  Reason: A ring is sitting on the bed.","YES",False),
("object named in Objects field","Objects: a small ring  Confidence: 90  Reason: there is a part left over.","YES",False),
("round object in a later sentence","Objects: none  Confidence: 100  Reason: The plate is empty. There is a round object visible in the corner.","YES",False),
("explicit 'not empty'","Objects: none  Confidence: 100  Reason: the bed is not empty.","YES",False),
("negated 'objects on the bed'","Objects: none  Confidence: 100  Reason: no objects on the bed, the plate is bare.","YES",True),
("'Nothing is sitting on the plate'","Objects: none  Confidence: 100  Reason: Nothing is sitting on the plate.","YES",True),
("negated list of all three HA shapes","Objects: none  Confidence: 100  Reason: The plate is completely bare, with no rings, donuts or trays present.","YES",True),
("first-layer GOOD 85 mentions rings of curling","GOOD  Confidence: 85  Observations: the lines are flat and even, with no rings of curling at the corners.","GOOD",True),
("first-layer GOOD mentions circular shape","GOOD  Confidence: 92  Observations: the circular shape is printing cleanly.","GOOD",True),
("REAL: missed spaghetti, failure check","Problems: Spaghetti  Confidence: 95  Reason: There are loose strands of filament tangled in the air, not attached to the object (spaghetti)","YES",True),
("healthy print mentioning spaghetti","Problems: none  Confidence: 90  Reason: no signs of spaghetti or detachment.","YES",False),
("hedged bed-clear at 60","Clear: YES  Confidence: 60  Reason: it looks clear enough.","YES",False),
("bare confidence, no verdict","Confidence: 95","YES",None),
("garbage input","   ","YES",None),
("plate called a 'tray' must NOT block","Objects: none  Confidence: 100  Reason: The print tray is clean and empty.","YES",True),
("model echoes the prompt's example objects, negated","Objects: none  Confidence: 98  Reason: There is no cup, ring, bracket, box or cylinder on the plate.","YES",True),
("prompt paraphrase about bed having no ring features","Objects: none  Confidence: 97  Reason: The bed is a flat rectangle with no circular or ring-shaped features.","YES",True),
("a real cylinder on the bed still blocks","Objects: none  Confidence: 96  Reason: A cylinder is resting on the plate.","YES",False),
("COMMA BUG: clear bed, comma-separated reply","Objects: none, Confidence: 100, Reason: The build plate is empty and clean.","YES",True),
("COMMA BUG: semicolon variant","Objects: none; Confidence: 100; Reason: nothing on the plate.","YES",True),
("comma variant must still block a real object","Objects: a cupholder, Confidence: 98, Reason: a part is on the plate.","YES",False),
("failure check: 'Problems: none,' must stay OK","Problems: none, Confidence: 95, Reason: the print looks healthy.","YES",False),
("model itself reports an object - blocked, and must carry NO note","Objects: a cupholder  Confidence: 98  Reason: a printed part is on the plate.","YES",False),
("model says Clear: NO - blocked, no note","Clear: NO  Confidence: 97  Reason: there is a part on the bed.","YES",False),
]
w=max(len(c[0]) for c in CASES); p=0
for label,text,kw,exp in CASES:
    got,why=parse_verdict(text,kw); ok=got==exp; p+=ok
    print("%s  %-*s exp=%-5s got=%-5s %s"%("PASS" if ok else "FAIL",w,label,exp,got,"(%s)"%why if why else ""))
print("\n%d/%d"%(p,len(CASES)))
