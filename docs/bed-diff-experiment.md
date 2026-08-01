# Does bed height drive the pixel-diff reading?

**Status:** not yet run. Fill in the table and the verdict, then delete the "not yet run".

## Why this exists

The pixel-diff backstop only discriminates while an empty bed reads near zero. On this fleet it
doesn't: empty beds read 4-7 against references a day old, and on 2026-08-01 an occupied bed read
**5.08** while the same bed empty had read **5.64** — the part scored *lower* than empty, so no
threshold separates them. A print started on top of an 8-inch speaker adapter as a result.

The leading hypothesis is **bed park height**. Bambu's end-gcode parks at `max_layer_z + 98mm`
(capped at Z248), so the plate stops at a different height after every differently-sized print,
appearing at a different position and scale in the camera frame. A reference captured at one height
then stops matching.

Supporting evidence, not yet conclusive:

- P1P measured **0.22** immediately after its reference was adopted (2026-07-31 02:14), then a
  rock-steady **6.57-6.58** across seven consecutive checks ~22 hours and six prints later, then
  **5.26**. A *step change* that persists, not drift and not noise.
- The AI Settings cards show Reference and Last measured side by side, and the plate visibly sits at
  a different position/scale in the two images.
- Self-refresh cannot recover from it: adoption requires ≤ half the limit (3.0), so once a printer
  drifts past that it is locked out permanently. It has fired exactly twice, ever.

**A previous session concluded "it is not geometry" and that conclusion is suspect.** It rested on
the zoom search choosing 0-1%, i.e. identity. But that search covers ±10% scale about the frame
centre and only **±3 pixels** of shift, while a bed moving tens of millimetres produces a much larger
translation plus a perspective change. A search too small to help returns "nothing found" whether or
not geometry is the cause, so it never discriminated. Don't treat that as settled.

## The experiment

Two minutes, no printing, fully reversible. **Move the bed DOWN, never up** — down increases
clearance from the nozzle, up risks a collision.

Pick any idle printer with a genuinely empty bed.

| # | Action | Where | Expected if geometry is the cause |
|---|---|---|---|
| 1 | **Save current frame** (capture reference) | AI Settings → Bed reference → that printer's card | — |
| 2 | **Measure now** | same card | ~0.2 |
| 3 | **Measure now** again, touching nothing | same card | ~0.2 — repeatability, rules out noise |
| 4 | Move Z **down** 20-30mm | Dashboard → printer → move controls | — |
| 5 | **Measure now** | AI Settings | **jumps to several units** |
| 6 | Check **"What differs"** | same card | red concentrated on the plate edge / rim |
| 7 | Move Z **back up** by the same amount | Dashboard | — |
| 8 | **Measure now** | AI Settings | **returns to ~0.2** |

### Results

| Step | Printer | mean | worst block | notes |
|---|---|---|---|---|
| 2 | | | | fresh reference |
| 3 | | | | untouched repeat |
| 5 | | | | bed moved down __ mm |
| 6 | | — | — | where the red landed: |
| 8 | | | | bed returned |

### Reading the result

**Step 8 is the one that matters.** A reading that rises when the bed moves and *returns* when it
moves back proves causation, not correlation.

- **5 jumps and 8 returns** → geometry confirmed. The metric is height-sensitive and the fix is to
  make it height-invariant. Leading option: keep **several references per printer**, captured at
  different heights, and score against the best match — an empty bed at any seen height matches
  something, a part matches none. No hardware movement, no collision risk, and self-refresh populates
  the set naturally. Cheaper thing to try first: widen the alignment search (shift ±30px, scale ±15%)
  and re-measure; if means collapse, that alone may be enough — but then measure a bed **with a part
  on it** to prove a wide search can't align a real object away.
- **5 does not jump** → geometry is not the cause. Next measurement: same empty bed, same reference,
  measured several hours apart with no prints in between. If it drifts then, the cause is time-based
  (lighting, camera auto-exposure/white-balance) and the answer is a different one.

## Until this is answered

`strictReference` (AI Settings → "Block when the reading can't be trusted") stays **OFF**. The rule
behind it is correct — a mid-range reading means the reference has stopped describing an empty bed,
and "cannot tell" must not read as "clear" — but with readings routinely landing at 4-6 on empty beds
it would block nearly every auto-start. Turning it on before the underlying drift is fixed trades a
silently unsafe farm for a loudly unusable one. Flip it on once an empty bed reliably measures near
zero regardless of what printed last.
