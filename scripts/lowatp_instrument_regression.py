#!/usr/bin/env python3
"""Prove the STEP-5/6 per-head instrumentation perturbs no simulated quantity.

Compares a re-run record against the pre-instrumentation baseline for the SAME
(ATP, eps, seed, duration). Every field that existed before the change must be
bit-identical; the new fields must be present in the re-run and absent (NaN) in
the baseline. Any difference in a pre-existing field means the added reductions
changed the trajectory, which would invalidate reusing the earlier pilot arms.

Usage: python3 scripts/lowatp_instrument_regression.py <baseline.tsv> <rerun.tsv>
"""
import sys


def read(path):
    vals, order, complete = {}, [], False
    with open(path) as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) != 2:
                continue
            if p[0] == "COMPLETE":
                complete = True; continue
            vals[p[0]] = p[1]          # keep the RAW string: bit-identity, not float equality
            order.append(p[0])
    return vals, order, complete


def main():
    if len(sys.argv) != 3:
        print(__doc__); return 2
    base, new = sys.argv[1], sys.argv[2]
    bv, border, bc = read(base)
    nv, norder, nc = read(new)
    if not (bc and nc):
        print("*** one of the records is not COMPLETE ***"); return 1

    shared = [k for k in border if k in nv]
    added = [k for k in norder if k not in bv]
    dropped = [k for k in border if k not in nv]

    # Fields whose value is INTENDED to change between these two builds, with the expected relation.
    # qOmega was corrected from the single-segment to the whole-filament roll drag (commit c3efc6a), so it
    # must change by EXACTLY the segment count -- a check, not an exemption.
    NSEG = 12
    intended = {}
    diffs = []
    for k in shared:
        if bv[k] == nv[k]:
            continue
        if k == "qOmega":
            a, b = float(bv[k]), float(nv[k])
            ratio = b / a if a else float("nan")
            intended[k] = (a, b, ratio, abs(ratio - NSEG) / NSEG < 1e-6)
            continue
        diffs.append((k, bv[k], nv[k]))

    print("=" * 92)
    print("INSTRUMENTATION REGRESSION — pre-existing fields must be BIT-IDENTICAL")
    print("=" * 92)
    print("  baseline : %s" % base)
    print("  re-run   : %s" % new)
    print("  pre-existing fields compared : %d" % len(shared))
    print("  fields with an INTENDED change: %d" % len(intended))
    print("  fields added by the change   : %d" % len(added))
    print("  fields dropped               : %d %s" % (len(dropped), dropped if dropped else ""))
    print()
    if diffs:
        print("  *** %d PRE-EXISTING FIELD(S) CHANGED — the instrumentation is NOT inert ***" % len(diffs))
        for k, a, b in diffs[:40]:
            print("      %-16s baseline=%-22s rerun=%s" % (k, a, b))
        print("\n  VERDICT: FAIL — earlier pilot arms are NOT reusable alongside re-instrumented arms.")
        return 1

    if intended:
        print("  INTENDED changes (verified against their expected relation, not waived):")
        for k, (a, b, ratio, ok) in intended.items():
            print("      %-14s %.10e -> %.10e   ratio %.6f vs expected NSEG=%d   %s"
                  % (k, a, b, ratio, NSEG, "EXACT" if ok else "*** UNEXPECTED RATIO ***"))
            if not ok:
                print("\n  VERDICT: FAIL — an intended field changed by the wrong factor.")
                return 1
        print()
    print("  every OTHER pre-existing field is byte-identical, including the chaotic trajectory endpoints:")
    for k in ("glide", "omegaFit", "tau", "avgBound", "turns", "rollR2", "nEp", "detachAtp", "residenceS"):
        if k in nv:
            print("      %-14s %s" % (k, nv[k]))
    print("\n  new fields now populated (absent in the baseline):")
    for k in added:
        print("      %-14s %s" % (k, nv[k]))
    print("\n  VERDICT: PASS — the added reductions are analysis-only and do not touch the simulation.")
    print("           The 16 earlier pilot arms remain valid alongside re-instrumented arms;")
    print("           they simply carry NaN in the new per-head fields.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
