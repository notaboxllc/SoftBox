#!/usr/bin/env python3
"""Analysis for the Vilfan LOW-OCCUPANCY Brownian confinement / phase-memory pilot.

Reads RUN_LOGS/vilfan_lowocc/*.json and emits, in order:
  0. OCC-1 occupancy-accounting gate
  1. MP-1 matched-path integrity  +  PW pathwise-mirror gates
  2. regime summary (occupancy, gaps, phase memory, velocity, twirl)
  3. conditional attachment analysis by category A/B/C/D vs matched shadow
  4. first-post-gap attachments binned by gap duration (preregistered bins)
  5. THE LOAD-BEARING TEST: S1 first-post-gap(gap > 10 ms) vs continuously tethered
  6. steady vs intermittent roll
  7. sufficiency gates
No parameter is fitted here; this script only reduces recorded quantities.

RECORD CONVENTION (easy to get wrong): catXa / catTh / catXi / binXa / binTh / binGap /
shadowXaByLabel are stored as MEANS already divided by their counts; catXa2 / binXa2 are raw sums of
squares; catN / binN / shadowWByLabel are counts / weights. Pooling across arms therefore has to
re-multiply the mean by its count.
"""
import json, glob, math, os, sys

RD = os.path.join(os.path.dirname(__file__), '..', 'RUN_LOGS', 'vilfan_lowocc')
CAT = ["first-post-gap", "early-post-gap", "tethered-sparse", "tethered-multihead"]
BIN = ["<0.5 ms", "0.5-2 ms", "2-10 ms", ">10 ms"]
REG = ["H", "S1", "K"]


def load(pat):
    out = {}
    for f in glob.glob(os.path.join(RD, pat)):
        r = json.load(open(f))
        out[r['armId']] = r
    return out


def fmt(x, w=8, p=3):
    if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))):
        return f"{'n/a':>{w}}"
    return f"{x:>{w}.{p}f}"


# ----------------------------------------------------------------- gate 0: OCC-1
def occ1(recs):
    print("\n## 0. OCC-1 occupancy accounting gate\n")
    print("Event-clock gap time divided by the committed zero-occupancy residence. Residence is")
    print("committed only when an interval CLOSES at a chemical event, so the two clocks are the same")
    print("decomposition summed in a different order: the gate's tolerance is therefore floating-point")
    print("summation error over N intervals, preregistered as 1e-12 relative. It is not vacuous -- it")
    print("compares the gap-detection path (inGap edge logic) against the occupancy histogram path,")
    print("and in this exact form it caught orphaned residence at the motor-field edge (ratio 0.912).\n")
    print(f"| arm | intervals | ratio | |1-ratio| | bad intervals | excess (s) | verdict |")
    print(f"|---|---|---|---|---|---|---|")
    ok = True
    for k in sorted(recs):
        r = recs[k]
        if r['nZeroIntervals'] == 0:
            print(f"| {k} | 0 | n/a | n/a | {r.get('nAcctBad',0)} | {r.get('acctExcess',0):.3g} | "
                  f"VACUOUS (no gaps) |")
            continue
        rat = r['gapAccountRatio']
        dev = abs(1.0 - rat)
        good = dev <= 1e-12 and r.get('nAcctBad', 0) == 0
        ok &= good
        print(f"| {k} | {r['nZeroIntervals']} | {rat:.15g} | {dev:.2g} | {r.get('nAcctBad',0)} | "
              f"{r.get('acctExcess',0):.3g} | {'PASS' if good else 'FAIL'} |")
    print(f"\n**OCC-1: {'PASS' if ok else 'FAIL'}**")
    return ok


# --------------------------------------------------- gate 1: matched path + mirror
def mp_pw(g):
    print("\n## 1. MP-1 matched-path integrity and PW pathwise-mirror gates\n")
    keys = ['velUmPerS', 'omegaRadPerS', 'meanXaNm', 'occMean', 'nEventsAnalysed', 'windingRad']
    print("MP-1: enabling the shadow read-out must not perturb the trajectory (it consumes no random")
    print("number, removes no motor and applies no force), so every arm quantity must be BIT-identical.\n")
    print("| regime | " + " | ".join(keys) + " | verdict |")
    print("|---" * (len(keys) + 2) + "|")
    ok1 = True
    for R in REG:
        a, b = g.get('gate_mp1off_' + R), g.get('gate_mp1on_' + R)
        if not a or not b:
            continue
        same = all(a[k] == b[k] for k in keys)
        ok1 &= same
        print(f"| {R} | " + " | ".join('identical' if a[k] == b[k] else f"{a[k]} vs {b[k]}"
                                      for k in keys) + f" | {'PASS' if same else 'FAIL'} |")
    print(f"\n**MP-1: {'PASS' if ok1 else 'FAIL'}**\n")

    print("PW: pathwise mirror at the SAME seed -- lattice mirrored, roll noise sign flipped")
    print("(mirror-ODD), axial noise untouched (mirror-EVEN). Roll must reverse; the even part")
    print("(half-sum) must sit far below the odd part (half-difference).\n")
    print("| regime | Omega native | Omega mirror | Omega_odd | Omega_even | |even/odd| | verdict |")
    print("|---|---|---|---|---|---|---|")
    ok2 = True
    for R in REG:
        n, m = g.get('gate_pw_nat_' + R), g.get('gate_pw_mir_' + R)
        if not n or not m:
            continue
        on, om = n['omegaRadPerS'], m['omegaRadPerS']
        odd, even = 0.5 * (on - om), 0.5 * (on + om)
        ratio = abs(even / odd) if odd else float('inf')
        good = ratio < 0.10
        ok2 &= good
        print(f"| {R} | {on:+.5g} | {om:+.5g} | {odd:+.5g} | {even:+.3g} | {ratio:.3g} | "
              f"{'PASS' if good else 'MARGINAL'} |")
    print(f"\n**PW: {'PASS' if ok2 else 'SEE NOTE'}**")
    return ok1, ok2


# ------------------------------------------------------------- 2. regime summary
def summary(p):
    print("\n## 2. Regime summary (native arms, both seeds)\n")
    print("| regime | seed | N_b | median | P(0) | gaps | mean gap (ms) | C_Theta | C_X | "
          "v (um/s) | Omega (rad/s) | <x_A> nm | before % |")
    print("|---" * 13 + "|")
    for R in REG:
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            nb = r['catN'][0] + r['catN'][1] + r['catN'][2] + r['catN'][3]
            bf = sum(r['catBefore'])
            print(f"| {R} | {s} | {r['occMean']:.3f} | {r['occMedianBinned']:.0f} | {r['pZero']:.4f} | "
                  f"{r['nGaps']} | {1e3*r['meanGapS']:.3f} | {fmt(r['cThetaMem'],6)} | "
                  f"{fmt(r['cXMem'],6)} | {r['velUmPerS']:.5f} | {r['omegaRadPerS']:+.4f} | "
                  f"{r['meanXaNm']:.4f} | {100.0*bf/nb if nb else float('nan'):.2f} |")


# ------------------------------------------- 3. conditional attachment analysis
def delta(real_xa, sh_xa):
    """Depletion contrast: how far the realised mean attachment position sits BEFORE the
    availability-blind expectation for the same part of the same trajectory."""
    return real_xa - sh_xa


def conditional(p):
    print("\n## 3. Conditional attachment analysis by occupancy category\n")
    print("Delta_depletion = <x_A>_real - <x_A>_shadow, both measured on the SAME realised trajectory,")
    print("the shadow restricted to the same occupancy/gap label. A NEGATIVE value means realised")
    print("attachments land further before the zone centre than blind availability predicts, which is")
    print("Vilfan target-zone depletion. Sign convention is fixed by the parent studies.\n")
    for R in REG:
        print(f"\n### Regime {R}\n")
        print("| seed | category | attachments | <x_A> real nm | <x_A> shadow nm | Delta nm | "
              "SEM nm | sigma | before % |")
        print("|---" * 9 + "|")
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            for c in range(4):
                n = r['catN'][c]
                if n == 0:
                    print(f"| {s} | {CAT[c]} | 0 | n/a | n/a | n/a | n/a | n/a | n/a |")
                    continue
                xa = r['catXa'][c]                       # already a mean
                shxa = r['shadowXaByLabel'][c]           # already a mean
                d = delta(xa, shxa)
                # per-category SEM from that category's OWN recorded spread
                if 'catXa2' in r and n > 1:
                    var = max(0.0, r['catXa2'][c] / n - xa * xa) * n / (n - 1.0)
                    sem = math.sqrt(var / n)
                else:
                    sd = r.get('sdXaNm') or float('nan')
                    sem = sd / math.sqrt(n) if sd == sd else float('nan')
                sig = abs(d) / sem if sem == sem and sem > 0 else float('nan')
                print(f"| {s} | {CAT[c]} | {n} | {xa:.4f} | {fmt(shxa,7,4)} | {d:+.4f} | "
                      f"{fmt(sem,7,4)} | {fmt(sig,6,1)} | {100.0*r['catBefore'][c]/n:.2f} |")


# ----------------------------------------- 4. first-post-gap by gap duration bin
def bins(p):
    print("\n## 4. First-post-gap attachments binned by gap duration (bins preregistered)\n")
    print("Bins <0.5 / 0.5-2 / 2-10 / >10 ms are fixed from the free-diffusion memory scales")
    print("(axial 1/e = 0.424 ms, angular 1/e = 2.045 ms) and were NOT redefined after seeing results.\n")
    for R in REG:
        print(f"\n### Regime {R}\n")
        print("| seed | bin | n | mean gap (ms) | <x_A> nm | before % | expected C_Theta | expected C_X |")
        print("|---" * 8 + "|")
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            for b in range(4):
                n = r['binN'][b]
                if n == 0:
                    print(f"| {s} | {BIN[b]} | 0 | n/a | n/a | n/a | | |")
                    continue
                gp = r['binGap'][b]                      # already a mean
                cth = math.exp(-r['DThetaRad2PerS'] * gp)
                cx = math.exp(-(2 * math.pi / 36.0) ** 2 * r['DXnm2PerS'] * gp)
                print(f"| {s} | {BIN[b]} | {n} | {1e3*gp:.3f} | {r['binXa'][b]:.4f} | "
                      f"{100.0*r['binBefore'][b]/n:.2f} | {cth:.4f} | {cx:.4f} |")


# ------------------------------------------------------- 5. the load-bearing test
def loadbearing(p):
    print("\n## 5. LOAD-BEARING TEST -- Regime S1: long-gap first-post-gap vs continuously tethered\n")
    print("If target-zone depletion is carried by PHASE MEMORY across the gap, an attachment that")
    print("follows a gap longer than every memory time (>10 ms: C_Theta < 7e-3, C_X < 1e-9) must show")
    print("a MARKEDLY WEAKER depletion bias than one taken while the filament stayed tethered. If the")
    print("two are equal, phase memory is NOT the carrier and the hypothesis is disproved.\n")
    print("| seed | quantity | first-post-gap (>10 ms) | continuously tethered | difference |")
    print("|---|---|---|---|---|")
    agg = {}
    for s in (101, 102):
        r = p.get(f'pil_S1_s{s}_native')
        if not r:
            continue
        nL, xL, bL = r['binN'][3], r['binXa'][3] * r['binN'][3], r['binBefore'][3]
        # continuously tethered = sparse + multihead
        nT = r['catN'][2] + r['catN'][3]
        xT = r['catXa'][2] * r['catN'][2] + r['catXa'][3] * r['catN'][3]
        bT = r['catBefore'][2] + r['catBefore'][3]
        agg.setdefault('nL', 0); agg['nL'] += nL; agg.setdefault('xL', 0.0); agg['xL'] += xL
        agg.setdefault('bL', 0); agg['bL'] += bL
        agg.setdefault('nT', 0); agg['nT'] += nT; agg.setdefault('xT', 0.0); agg['xT'] += xT
        agg.setdefault('bT', 0); agg['bT'] += bT
        if nL and nT:
            print(f"| {s} | n | {nL} | {nT} | |")
            print(f"| {s} | <x_A> nm | {xL/nL:+.4f} | {xT/nT:+.4f} | {xL/nL - xT/nT:+.4f} |")
            print(f"| {s} | before-centre % | {100*bL/nL:.2f} | {100*bT/nT:.2f} | "
                  f"{100*bL/nL - 100*bT/nT:+.2f} |")
    if agg.get('nL') and agg.get('nT'):
        xl, xt = agg['xL'] / agg['nL'], agg['xT'] / agg['nT']
        pl, pt = agg['bL'] / agg['nL'], agg['bT'] / agg['nT']
        sep = math.sqrt(pl * (1 - pl) / agg['nL'] + pt * (1 - pt) / agg['nT'])
        z = (pl - pt) / sep if sep > 0 else float('nan')
        print(f"\n**Pooled (both seeds):** n = {agg['nL']} long-gap vs {agg['nT']} tethered; "
              f"<x_A> {xl:+.4f} vs {xt:+.4f} nm (difference {xl-xt:+.4f}); before-centre "
              f"{100*pl:.2f} % vs {100*pt:.2f} % (difference {100*(pl-pt):+.2f} pp, {z:+.2f} sigma "
              f"on the binomial proportions).")


# --------------------------------------------------- 6. steady vs intermittent roll
def lsq(t, y):
    """least-squares slope and R^2 of y against t."""
    n = len(t)
    if n < 3:
        return float('nan'), float('nan')
    mt = sum(t) / n; my = sum(y) / n
    stt = sum((a - mt) ** 2 for a in t)
    sty = sum((a - mt) * (b - my) for a, b in zip(t, y))
    syy = sum((b - my) ** 2 for b in y)
    if stt <= 0 or syy <= 0:
        return float('nan'), float('nan')
    sl = sty / stt
    return sl, (sty * sty) / (stt * syy)


def classify(r, blocks, r2, share10, signfrac):
    """steady drift | noisy drift | intermittent signed bursts | symmetric diffusion | no gliding."""
    if abs(r.get('velUmPerS', 0.0)) < 2e-3:
        return "no sustained gliding"
    pos = sum(1 for b in blocks if b > 0)
    stab = max(pos, len(blocks) - pos) / len(blocks) if blocks else float('nan')
    if stab < 0.65:
        return "symmetric diffusion"
    if share10 is not None and share10 > 0.60:
        return "intermittent signed bursts"
    if r2 == r2 and r2 > 0.90:
        return "steady drift"
    return "noisy drift"


def episodes(p):
    print("\n## 6. Steady versus intermittent roll\n")
    print("Roll is attributed per EPISODE (a contiguous stretch with N_b >= 1); roll during a")
    print("zero-bound gap is free rotational diffusion and carries no mechanism. Torque impulse per")
    print("episode is exact in the overdamped model: integral(M dt) = gamma_Theta * dTheta, with")
    print("gamma_Theta = kBT / D_Theta taken from the arm's own recorded D_Theta.\n")
    print("| regime | seed | episodes | mean dur (ms) | sum dTheta | mean impulse (pN nm s) | "
          "top 1% | top 5% | top 10% | sign + % |")
    print("|---" * 10 + "|")
    for R in REG:
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            d, du = r.get('epDTh') or [], r.get('epDur') or []
            if not d:
                print(f"| {R} | {s} | 0 (single uninterrupted episode) | | | | | | | |")
                continue
            gt = r['kBT'] / r['DThetaRad2PerS']
            tot = sum(d)
            srt = sorted(d, key=lambda v: -abs(v))
            def share(f):
                k = max(1, int(math.ceil(f * len(srt))))
                return sum(srt[:k]) / tot if tot else float('nan')
            pos = sum(1 for v in d if v > 0)
            print(f"| {R} | {s} | {len(d)} | {1e3*sum(du)/len(du):.3f} | {tot:+.4g} | "
                  f"{gt*tot/len(d):+.4g} | {share(0.01):+.3f} | {share(0.05):+.3f} | "
                  f"{share(0.10):+.3f} | {100.0*pos/len(d):.1f} |")

    print("\n### Blockwise rotation, drift quality and motion class\n")
    print("| regime | seed | Omega full | 1st half | 2nd half | blocks | Omega block mean +- sd | "
          "sign stability % | roll R^2 | axial R^2 | class |")
    print("|---" * 11 + "|")
    for R in REG:
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            T, X, TH = r.get('trcT') or [], r.get('trcX') or [], r.get('trcTh') or []
            blocks, r2th, r2x = [], float('nan'), float('nan')
            if len(T) > 40:
                nb_ = 20; per = len(T) // nb_
                for k in range(nb_):
                    sl = slice(k * per, (k + 1) * per)
                    b, _ = lsq(T[sl], TH[sl])
                    if b == b:
                        blocks.append(b)
                r2th = lsq(T, TH)[1]
                r2x = lsq(T, X)[1]
            if blocks:
                m = sum(blocks) / len(blocks)
                sd = math.sqrt(sum((b - m) ** 2 for b in blocks) / max(1, len(blocks) - 1))
                pos = sum(1 for b in blocks if b > 0)
                stab = 100.0 * max(pos, len(blocks) - pos) / len(blocks)
            else:
                m = sd = stab = float('nan')
            d = r.get('epDTh') or []
            sh10 = None
            if d:
                tot = sum(d); srt = sorted(d, key=lambda v: -abs(v))
                k = max(1, int(math.ceil(0.10 * len(srt))))
                sh10 = abs(sum(srt[:k]) / tot) if tot else None
            cl = classify(r, blocks, r2th, sh10, stab)
            print(f"| {R} | {s} | {r['omegaRadPerS']:+.4f} | {r['omegaFirstHalf']:+.4f} | "
                  f"{r['omegaSecondHalf']:+.4f} | {len(blocks)} | {m:+.4f} +- {sd:.4f} | "
                  f"{stab:.0f} | {fmt(r2th,6,4)} | {fmt(r2x,6,4)} | {cl} |")

    print("\n### Angular displacement by instantaneous occupancy, and motor-free time\n")
    print("| regime | seed | dTheta at N_b=0 | 1 | 2 | >=3 | time at 0 (s) | 1 | 2 | >=3 | "
          "motor-free substeps | motor-free % |")
    print("|---" * 11 + "|")
    for R in REG:
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            rb = r['rollByOcc']; tb = r.get('timeByOcc') or [float('nan')] * 4
            nz = r.get('nZeroHazSubsteps', 0)
            tot = r.get('nSubsteps', 0) or 1
            print(f"| {R} | {s} | " + " | ".join(f"{v:+.4g}" for v in rb) + " | " +
                  " | ".join(fmt(v, 6, 3) for v in tb) + f" | {nz} | {100.0*nz/tot:.2f} |")

    print("\n### Waiting time between signed roll bursts\n")
    print("A BURST is an episode whose |dTheta| exceeds the median episode |dTheta|; the waiting time")
    print("is the interval between successive burst onsets. An exponential-like distribution")
    print("(sd ~ mean) indicates Poisson bursts; sd << mean indicates near-periodic engagement.\n")
    print("| regime | seed | bursts | mean wait (ms) | sd (ms) | sd/mean | median wait (ms) |")
    print("|---|---|---|---|---|---|---|")
    for R in REG:
        for s in (101, 102):
            r = p.get(f'pil_{R}_s{s}_native')
            if not r:
                continue
            d, t0 = r.get('epDTh') or [], r.get('epT0') or []
            if len(d) < 6 or len(t0) != len(d):
                print(f"| {R} | {s} | {len(d)} | n/a | n/a | n/a | n/a |")
                continue
            med = sorted(abs(v) for v in d)[len(d) // 2]
            bt = [t0[i] for i in range(len(d)) if abs(d[i]) > med]
            if len(bt) < 3:
                print(f"| {R} | {s} | {len(bt)} | n/a | n/a | n/a | n/a |")
                continue
            w = [bt[i + 1] - bt[i] for i in range(len(bt) - 1)]
            mw = sum(w) / len(w)
            sdw = math.sqrt(sum((x - mw) ** 2 for x in w) / max(1, len(w) - 1))
            print(f"| {R} | {s} | {len(bt)} | {1e3*mw:.3f} | {1e3*sdw:.3f} | {sdw/mw:.3f} | "
                  f"{1e3*sorted(w)[len(w)//2]:.3f} |")


# ------------------------------------------------- 6b. inferential mirror pairs
def mirror_pairs(p):
    print("\n## 6b. Inferential mirror pairs (independent noise)\n")
    print("Requirements: Omega_even consistent with zero; depletion bias mirror-EVEN; angular bias and")
    print("torque mirror-ODD. With independent noise these hold in expectation, not pathwise, so the")
    print("comparison is against the seed-to-seed spread.\n")
    print("| regime | seed | Omega nat | Omega mir | Omega_odd | Omega_even | <x_A> nat | <x_A> mir | "
          "<th_A> nat | <th_A> mir | torque nat | torque mir |")
    print("|---" * 12 + "|")
    for R in REG:
        for s in (101, 102):
            n, m = p.get(f'pil_{R}_s{s}_native'), p.get(f'pil_{R}_s{s}_mirror')
            if not n or not m:
                continue
            on, om = n['omegaRadPerS'], m['omegaRadPerS']
            print(f"| {R} | {s} | {on:+.4f} | {om:+.4f} | {0.5*(on-om):+.4f} | {0.5*(on+om):+.4f} | "
                  f"{n['meanXaNm']:+.3f} | {m['meanXaNm']:+.3f} | {n['meanThARad']:+.4f} | "
                  f"{m['meanThARad']:+.4f} | {n['meanAttachTorquePnNm']:+.4g} | "
                  f"{m['meanAttachTorquePnNm']:+.4g} |")


# ------------------------------------------------------------- 7. sufficiency gates
def sufficiency(p):
    print("\n## 7. Pilot sufficiency gates\n")
    g = []
    S = native_arms(p, 'S1')            # includes the sufficiency extension
    s1g = sum(r['nGaps'] for r in S)
    s1f = sum(r['catN'][0] for r in S)
    s1t = sum(r['catN'][2] for r in S)
    s1L = sum(r['binN'][3] for r in S)
    hA = sum(sum(r['catN']) for r in native_arms(p, 'H'))
    g.append(("S1 zero-bound gaps >= 100", s1g, 100))
    g.append(("S1 first-post-gap attachments >= 100", s1f, 100))
    g.append(("S1 long-gap (>10 ms) first-post-gap >= 30", s1L, 30))
    g.append(("S1 tethered-sparse attachments >= 1000", s1t, 1000))
    g.append(("H attachments >= 10000", hA, 10000))
    print("| gate | observed | required | verdict |")
    print("|---|---|---|---|")
    allok = True
    for nm, o, req in g:
        ok = o >= req
        allok &= ok
        print(f"| {nm} | {o} | {req} | {'PASS' if ok else 'SHORT'} |")
    hal = [k for k in p if p[k]['note'].strip().strip('"')]
    print(f"\nArms carrying a note (halt / cap): {hal if hal else 'none'}")
    capf = [k for k in p if p[k].get('nRollCapFail', 0) > 0]
    print(f"Arms with roll-cap failures: {capf if capf else 'none'} (must be none)")
    print(f"\n**Sufficiency: {'PASS' if allok else 'PARTIAL -- see SHORT rows'}**")
    return allok


# ------------------------------------------------ 8. pooled conditional deltas
def native_arms(p, reg):
    """All NATIVE arms of a regime. For S1 this includes the sufficiency-extension arms, which is
    where the load-bearing statistics live: the two briefed 120 s seeds both landed on dense field
    realisations and yielded 6 and 9 gaps."""
    out = []
    for k, r in p.items():
        if not k.endswith('_native'):
            continue
        if k.startswith(f'pil_{reg}_') or (reg == 'S1' and k.startswith('s1x_')):
            out.append(r)
    return out


def pooled_delta(p, reg, cats):
    """pool a set of categories over both seeds; return (n, <x_A>, delta vs shadow, sem, sd)."""
    n = 0; sx = 0.0; sx2 = 0.0; sw = 0.0; swx = 0.0
    for r in native_arms(p, reg):
        for c in cats:
            n += r['catN'][c]; sx += r['catXa'][c] * r['catN'][c]; sx2 += r['catXa2'][c]
            w = r['shadowWByLabel'][c]
            sw += w; swx += (r['shadowXaByLabel'][c] * w if w > 0 else 0.0)
    if n < 2:
        return n, float('nan'), float('nan'), float('nan'), float('nan')
    xa = sx / n
    var = max(0.0, sx2 / n - xa * xa) * n / (n - 1.0)
    sem = math.sqrt(var / n)
    sh = swx / sw if sw > 0 else float('nan')
    return n, xa, xa - sh, sem, math.sqrt(var)


def pooled_bin(p, reg, b):
    n = 0; sx = 0.0; sx2 = 0.0
    for r in native_arms(p, reg):
        n += r['binN'][b]; sx += r['binXa'][b] * r['binN'][b]; sx2 += r['binXa2'][b]
    if n < 2:
        return n, float('nan'), float('nan'), float('nan')
    xa = sx / n
    var = max(0.0, sx2 / n - xa * xa) * n / (n - 1.0)
    return n, xa, math.sqrt(var / n), math.sqrt(var)


def arm_stat(r, cats=None, binidx=None):
    """(n, <x_A>, before-centre fraction) for one arm and one category set."""
    if binidx is None:
        n = sum(r['catN'][c] for c in cats)
        if n == 0:
            return None
        x = sum(r['catXa'][c] * r['catN'][c] for c in cats) / n
        b = sum(r['catBefore'][c] for c in cats) / n
    else:
        n = r['binN'][binidx]
        if n == 0:
            return None
        x, b = r['binXa'][binidx], r['binBefore'][binidx] / n
    return n, x, b


def mean_sem(v):
    k = len(v)
    if k < 2:
        return float('nan'), float('nan'), float('nan')
    mu = sum(v) / k
    sd = math.sqrt(sum((x - mu) ** 2 for x in v) / (k - 1))
    sem = sd / math.sqrt(k)
    return mu, sem, (mu / sem if sem > 0 else float('nan'))


def paired(p, reg, minn=30):
    """WITHIN-ARM contrast, long-gap first-post-gap MINUS continuously tethered.

    This, not the pooled contrast, is the correct estimator. In the sparse regime the ABSOLUTE
    before-centre fraction of tethered attachments swings from 19 % to 69 % between motor-field
    realisations, while the long-gap fraction sits at ~50 % in every one. Pooling attachments across
    arms therefore compares a stable ~49 % against a count-weighted mixture of a swinging quantity and
    manufactures a contrast that is absent within any arm -- a textbook Simpson reversal. Pooling gave
    -5.8 sigma; the paired estimator over the same 16 arms gives -0.54 sigma.
    """
    rows, dx, db = [], [], []
    for k in sorted(p):
        if not (k.startswith(f'pil_{reg}_') or (reg == 'S1' and k.startswith('s1x_'))):
            continue
        r = p[k]
        T, L = arm_stat(r, cats=[2, 3]), arm_stat(r, binidx=3)
        if not T or not L or T[0] < minn or L[0] < minn:
            continue
        rows.append((k, T[0], L[0], 100 * T[2], 100 * L[2], 100 * (L[2] - T[2])))
        dx.append(L[1] - T[1]); db.append(100 * (L[2] - T[2]))
    return rows, dx, db


def paired_report(p):
    print("\n## 8b. WITHIN-ARM paired contrast (the primary estimator)\n")
    print(paired.__doc__.split('\n\n', 1)[1].replace('    ', ''))
    out = {}
    for R in REG:
        rows, dx, db = paired(p, R)
        if not rows:
            print(f"\n### Regime {R}: no arm has >= 30 attachments in BOTH categories\n")
            continue
        print(f"\n### Regime {R}\n")
        print("| arm | n tethered | n long-gap | tethered before % | long-gap before % | difference pp |")
        print("|---|---|---|---|---|---|")
        for a_ in rows:
            print(f"| {a_[0]} | {a_[1]} | {a_[2]} | {a_[3]:.2f} | {a_[4]:.2f} | {a_[5]:+.2f} |")
        mb, sb, zb = mean_sem(db)
        mx, sx, zx = mean_sem(dx)
        tb = [r_[3] for r_ in rows]; lb = [r_[4] for r_ in rows]
        mt, st, _ = mean_sem(tb); ml, sl, _ = mean_sem(lb)
        print(f"\n- paired before-centre difference: **{mb:+.2f} +- {sb:.2f} pp = {zb:+.2f} sigma** "
              f"over {len(db)} arms")
        print(f"- paired <x_A> difference: **{mx:+.4f} +- {sx:.4f} nm = {zx:+.2f} sigma**")
        print(f"- tethered before-centre across arms: {mt:.2f} +- {st:.2f} % "
              f"(range {min(tb):.2f}-{max(tb):.2f})")
        print(f"- long-gap before-centre across arms: {ml:.2f} +- {sl:.2f} % "
              f"(range {min(lb):.2f}-{max(lb):.2f})")
        out[R] = dict(nb=len(db), mb=mb, sb=sb, zb=zb, mx=mx, sx=sx,
                      mt=mt, st=st, ml=ml, sl=sl,
                      sdb=sb * math.sqrt(len(db)), sdx=sx * math.sqrt(len(dx)),
                      sdt=st * math.sqrt(len(tb)))
    return out


def decision(p):
    print("\n## 8. Pooled conditional contrast and pilot outcome\n")
    print("| regime | set | n | <x_A> nm | SEM | Delta vs shadow nm | sigma |")
    print("|---|---|---|---|---|---|---|")
    res = {}
    for R in REG:
        for nm, cats in (("first-post-gap", [0]), ("early-post-gap", [1]),
                         ("tethered (sparse+multi)", [2, 3])):
            n, xa, d, sem, sd = pooled_delta(p, R, cats)
            sig = abs(d) / sem if sem == sem and sem > 0 else float('nan')
            res[(R, nm)] = (n, xa, d, sem, sd)
            print(f"| {R} | {nm} | {n} | {fmt(xa,8,4)} | {fmt(sem,7,4)} | {fmt(d,8,4)} | "
                  f"{fmt(sig,6,1)} |")
    print()
    for R in REG:
        for b in range(4):
            n, xa, sem, sd = pooled_bin(p, R, b)
            res[(R, 'bin%d' % b)] = (n, xa, sem, sd)
            print(f"| {R} | first-post-gap {BIN[b]} | {n} | {fmt(xa,8,4)} | {fmt(sem,7,4)} | | |")

    # ---- the decision ----
    print("\n### Outcome assignment\n")
    bef = {}
    for reg in REG:
        for lbl, cats in (('teth', [2, 3]),):
            nb_ = sum(r['catN'][c] for r in native_arms(p, reg) for c in cats)
            bb_ = sum(r['catBefore'][c] for r in native_arms(p, reg) for c in cats)
            bef[(reg, lbl)] = (nb_, bb_)
        nb_ = sum(r['binN'][3] for r in native_arms(p, reg))
        bb_ = sum(r['binBefore'][3] for r in native_arms(p, reg))
        bef[(reg, 'bin3')] = (nb_, bb_)
    nL, xL, semL, sdL = res.get(('S1', 'bin3'), (0,) * 4)
    nT, xT, dT, semT, sdT = res.get(('S1', 'tethered (sparse+multi)'), (0,) * 5)
    nF, xF, dF, semF, sdF = res.get(('S1', 'first-post-gap'), (0,) * 5)
    lines = []
    if nL >= 30 and nT >= 30 and semL == semL and semT == semT:
        diff = xL - xT
        sed = math.sqrt(semL ** 2 + semT ** 2)
        z = diff / sed if sed > 0 else float('nan')
        lines.append(f"S1 long-gap (>10 ms) <x_A> = {xL:+.4f} +- {semL:.4f} nm (n = {nL}); "
                     f"continuously tethered <x_A> = {xT:+.4f} +- {semT:.4f} nm (n = {nT}); "
                     f"difference {diff:+.4f} +- {sed:.4f} nm = {z:+.2f} sigma.")
        nLb, bLb = bef[('S1', 'bin3')]
        nTb, bTb = bef[('S1', 'teth')]
        if nLb and nTb:
            pL, pT = bLb / nLb, bTb / nTb
            sep = math.sqrt(pL * (1 - pL) / nLb + pT * (1 - pT) / nTb)
            zp = (pL - pT) / sep if sep > 0 else float('nan')
            lines.append(f"before-centre fraction: long-gap {100*pL:.2f} % (n = {nLb}) against "
                         f"tethered {100*pT:.2f} % (n = {nTb}) = {zp:+.2f} sigma.")
        if abs(z) < 2.0:
            lines.append("The two are statistically INDISTINGUISHABLE -> the phase-memory hypothesis "
                         "is NOT supported by the load-bearing test (P5 direction).")
        elif abs(xL) < 0.5 * abs(xT):
            lines.append("Long-gap depletion is strongly REDUCED relative to tethered -> P1 direction.")
        else:
            lines.append("Long-gap and tethered differ but long-gap bias is NOT strongly reduced.")
    else:
        lines.append(f"Insufficient long-gap statistics (n = {nL}) or tethered (n = {nT}) for the "
                     f"load-bearing comparison -> P7 direction.")
    for L in lines:
        print("- " + L)
    return res


# --------------------------------------------------- 9. campaign sizing
def sizing(p, res):
    print("\n## 9. Sizing the reduced production campaign\n")
    print("Per-attachment spread sd is measured here; the seeds needed for a target resolution follow")
    print("from the observed attachment RATE per arm, so the design variable is simulated seconds.\n")
    print("| regime | category | sd (nm) | attachments per analysed second | "
          "seconds for SEM 0.10 nm | seconds for SEM 0.05 nm |")
    print("|---|---|---|---|---|---|")
    for R in REG:
        for nm in ("first-post-gap", "tethered (sparse+multi)"):
            v = res.get((R, nm))
            if not v:
                continue
            n, xa, d, sem, sd = v
            tot = 0.0
            for s in (101, 102):
                r = p.get(f'pil_{R}_s{s}_native')
                if r:
                    tot += r['occTotalTime']
            rate = n / tot if tot > 0 else float('nan')
            def need(target):
                if not (sd == sd) or rate <= 0:
                    return float('nan')
                return (sd / target) ** 2 / rate
            print(f"| {R} | {nm} | {fmt(sd,7,3)} | {fmt(rate,7,2)} | {fmt(need(0.10),9,1)} | "
                  f"{fmt(need(0.05),9,1)} |")


def sizing_paired(pair):
    print("\n## 9b. Sizing from the PAIRED per-arm variance (the design that matters)\n")
    print("The design variable is the number of motor-field REALISATIONS, not the run length: the")
    print("per-arm scatter is set by which motors the filament sits on. Arms are 480 s (S1) / 120 s (K).\n")
    print("| regime | arms so far | per-arm sd, before-centre (pp) | arms for SEM 2 pp | SEM 1 pp | "
          "per-arm sd, <x_A> (nm) | arms for SEM 0.3 nm |")
    print("|---|---|---|---|---|---|---|")
    for R, d in pair.items():
        n2 = (d['sdb'] / 2.0) ** 2
        n1 = (d['sdb'] / 1.0) ** 2
        nx = (d['sdx'] / 0.3) ** 2
        print(f"| {R} | {d['nb']} | {d['sdb']:.2f} | {n2:.0f} | {n1:.0f} | {d['sdx']:.3f} | {nx:.0f} |")
    print("\nTo ask whether the sparse-regime TETHERED bias differs from H's 58.7 %, the relevant")
    print("spread is the per-arm tethered before-centre sd:\n")
    print("| regime | per-arm sd (pp) | arms for SEM 2 pp | current mean +- SEM | separation from H |")
    print("|---|---|---|---|---|")
    for R, d in pair.items():
        n2 = (d['sdt'] / 2.0) ** 2
        sep = abs(d['mt'] - 58.7) / d['st'] if d['st'] > 0 else float('nan')
        print(f"| {R} | {d['sdt']:.2f} | {n2:.0f} | {d['mt']:.2f} +- {d['st']:.2f} % | {sep:.2f} sigma |")


if __name__ == '__main__':
    gates = load('gate_*.json')
    pil = load('pil_*.json')
    ext = load('s1x_*.json')
    print("# Vilfan low-occupancy mechanism pilot -- reduction")
    if gates:
        occ1(gates)
        mp_pw(gates)
    if pil:
        pil = dict(pil, **ext)          # S1 statistics pool the sufficiency extension
        occ1(pil)
        summary(pil)
        conditional(pil)
        bins(pil)
        loadbearing(pil)
        episodes(pil)
        mirror_pairs(pil)
        _pair = paired_report(pil)
        _res = decision(pil)
        sizing(pil, _res)
        sizing_paired(_pair)
        sufficiency(pil)
    else:
        print("\n(no pilot records yet)")
    if ext:
        print("\n\n# S1 sufficiency extension (8 field realisations x 480 s)\n")
        occ1(ext)
        print("\n## E1. Per-realisation spread at rho = 0.35 /um\n")
        print("| field seed | arm | N_b | P(0) | gaps | mean gap (ms) | max gap (s) | motor-free % | "
              "v (um/s) | Omega |")
        print("|---" * 10 + "|")
        for k in sorted(ext):
            r = ext[k]
            mf = 100.0 * r['motorFreeTimeS'] / max(1e-9, r['occTotalTime'])
            fs = k.split('_')[1].lstrip('s')
            print(f"| {fs} | {k} | {r['occMean']:.3f} | {r['pZero']:.4f} | "
                  f"{r['nGaps']} | {1e3*r['meanGapS']:.2f} | {r['maxGapS']:.3f} | {mf:.1f} | "
                  f"{r['velUmPerS']:.5f} | {r['omegaRadPerS']:+.4f} |")
        print("\n## E2. Pooled conditional contrast over all realisations\n")
        pooled = {'S1': ext}
        print("| set | n | <x_A> nm | SEM | Delta vs shadow nm | sigma | before % |")
        print("|---|---|---|---|---|---|---|")
        def pool(cats, binidx=None):
            n = 0; sx = 0.0; sx2 = 0.0; sw = 0.0; swx = 0.0; bf = 0
            for r in ext.values():
                if binidx is None:
                    for c in cats:
                        n += r['catN'][c]; sx += r['catXa'][c] * r['catN'][c]
                        sx2 += r['catXa2'][c]
                        w_ = r['shadowWByLabel'][c]
                        sw += w_; swx += (r['shadowXaByLabel'][c] * w_ if w_ > 0 else 0.0)
                        bf += r['catBefore'][c]
                else:
                    n += r['binN'][binidx]; sx += r['binXa'][binidx] * r['binN'][binidx]
                    sx2 += r['binXa2'][binidx]
                    bf += r['binBefore'][binidx]
            if n < 2:
                return None
            xa = sx / n
            var = max(0.0, sx2 / n - xa * xa) * n / (n - 1.0)
            sem = math.sqrt(var / n)
            sh = swx / sw if sw > 0 else float('nan')
            return n, xa, sem, (xa - sh if sh == sh else float('nan')), math.sqrt(var), bf
        rows = [("first-post-gap", pool([0])), ("early-post-gap", pool([1])),
                ("tethered-sparse", pool([2])), ("tethered-multihead", pool([3])),
                ("tethered (sparse+multi)", pool([2, 3]))]
        for b_ in range(4):
            rows.append((f"first-post-gap {BIN[b_]}", pool(None, b_)))
        store = {}
        for nm, v in rows:
            if not v:
                print(f"| {nm} | 0 | | | | | |")
                continue
            n, xa, sem, dlt, sd, bf = v
            store[nm] = v
            sig = abs(dlt) / sem if sem > 0 and dlt == dlt else float('nan')
            print(f"| {nm} | {n} | {xa:+.4f} | {sem:.4f} | {fmt(dlt,8,4)} | {fmt(sig,6,1)} | "
                  f"{100.0*bf/n:.2f} |")
        print("\n### E3. LOAD-BEARING TEST, pooled over realisations\n")
        L = store.get(f"first-post-gap {BIN[3]}")
        T = store.get("tethered (sparse+multi)")
        if L and T:
            nL, xL, semL, _, sdL, bL = L
            nT, xT, semT, _, sdT, bT = T
            sed = math.sqrt(semL ** 2 + semT ** 2)
            z = (xL - xT) / sed if sed > 0 else float('nan')
            pL, pT = bL / nL, bT / nT
            sep = math.sqrt(pL * (1 - pL) / nL + pT * (1 - pT) / nT)
            zp = (pL - pT) / sep if sep > 0 else float('nan')
            print(f"- long-gap (>10 ms) first-post-gap: n = {nL}, <x_A> = {xL:+.4f} +- {semL:.4f} nm, "
                  f"before-centre {100*pL:.2f} %")
            print(f"- continuously tethered:            n = {nT}, <x_A> = {xT:+.4f} +- {semT:.4f} nm, "
                  f"before-centre {100*pT:.2f} %")
            print(f"- difference in <x_A>: {xL-xT:+.4f} +- {sed:.4f} nm = **{z:+.2f} sigma**")
            print(f"- difference in before-centre fraction: {100*(pL-pT):+.2f} pp = **{zp:+.2f} sigma**")

        print("\n## E4. S1 mirror pairs over 8 field realisations (independent noise, SHARED field)\n")
        print("| field | Omega nat | Omega mir | Omega_odd | Omega_even | <x_A> nat | <x_A> mir | "
              "<th_A> nat | <th_A> mir | torque nat | torque mir |")
        print("|---" * 11 + "|")
        odds, evens, xen, xem = [], [], [], []
        for fs in range(101, 109):
            n = ext.get(f's1x_s{fs}_native'); m = ext.get(f's1x_s{fs}_mirror')
            if not n or not m:
                continue
            on, om = n['omegaRadPerS'], m['omegaRadPerS']
            odds.append(0.5 * (on - om)); evens.append(0.5 * (on + om))
            xen.append(n['meanXaNm']); xem.append(m['meanXaNm'])
            print(f"| {fs} | {on:+.4f} | {om:+.4f} | {0.5*(on-om):+.4f} | {0.5*(on+om):+.4f} | "
                  f"{n['meanXaNm']:+.3f} | {m['meanXaNm']:+.3f} | {n['meanThARad']:+.5f} | "
                  f"{m['meanThARad']:+.5f} | {n['meanAttachTorquePnNm']:+.4g} | "
                  f"{m['meanAttachTorquePnNm']:+.4g} |")
        def ms(v):
            k = len(v)
            if k < 2:
                return float('nan'), float('nan'), float('nan')
            mu = sum(v) / k
            sd = math.sqrt(sum((x - mu) ** 2 for x in v) / (k - 1))
            return mu, sd / math.sqrt(k), abs(mu) / (sd / math.sqrt(k)) if sd > 0 else float('nan')
        for nm, v in (("Omega_odd", odds), ("Omega_even", evens)):
            mu, sem, sig = ms(v)
            pos = sum(1 for x in v if x > 0)
            print(f"\n- **{nm}** over {len(v)} realisations: {mu:+.4f} +- {sem:.4f} rad/s = "
                  f"{sig:.2f} sigma; sign split {pos}/{len(v)} positive")

        print("\n## E5. Steady versus intermittent roll, sparse regime\n")
        print("| field | arm | episodes | mean dur (ms) | sum dTheta | top 1% | top 5% | top 10% | "
              "sign + % | roll R^2 | blocks sign stab % | class |")
        print("|---" * 12 + "|")
        for k in sorted(ext):
            r = ext[k]
            d, du = r.get('epDTh') or [], r.get('epDur') or []
            T, X, TH = r.get('trcT') or [], r.get('trcX') or [], r.get('trcTh') or []
            blocks, r2th = [], float('nan')
            if len(T) > 40:
                per = len(T) // 20
                for q_ in range(20):
                    b_, _ = lsq(T[q_ * per:(q_ + 1) * per], TH[q_ * per:(q_ + 1) * per])
                    if b_ == b_:
                        blocks.append(b_)
                r2th = lsq(T, TH)[1]
            stab = (100.0 * max(sum(1 for b_ in blocks if b_ > 0),
                                len(blocks) - sum(1 for b_ in blocks if b_ > 0)) / len(blocks)
                    if blocks else float('nan'))
            if not d:
                print(f"| {k.split('_')[1].lstrip('s')} | {k} | 0 | | | | | | | | | |")
                continue
            tot = sum(d); srt = sorted(d, key=lambda v: -abs(v))
            def sh(f):
                q2 = max(1, int(math.ceil(f * len(srt))))
                return sum(srt[:q2]) / tot if tot else float('nan')
            pos = sum(1 for v in d if v > 0)
            cl = classify(r, blocks, r2th, abs(sh(0.10)) if tot else None, stab)
            print(f"| {k.split('_')[1].lstrip('s')} | {k} | {len(d)} | {1e3*sum(du)/len(du):.2f} | "
                  f"{tot:+.4g} | {sh(0.01):+.3f} | {sh(0.05):+.3f} | {sh(0.10):+.3f} | "
                  f"{100.0*pos/len(d):.1f} | {fmt(r2th,6,4)} | {fmt(stab,5,0)} | {cl} |")
