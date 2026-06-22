package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Node-SPECIFIC system (increment 6c, Stage A): the RADIAL surface tether + the SINGLE-ENDED one-pass
 * gather. Faithful port of v1 ProteinNode.keepMyosinsOnSurface / keepMyosinDimersOnSurface (BoA-v1ref):
 * each child's myoRod end1 is tethered to a body-frame RADIAL point on the node sphere surface by a
 * damping-limited fracMove spring. NO axis-alignment torque (unlike the minifilament tether).
 *
 * COUPLING (recon §1a/§4a): the node OWNS its children (one consumer, many writers, each child keyed to
 * exactly one node via attachNode) — the SAME single-ended shape as the minifilament backbone↔dimer
 * gather (motor→segment / headBackboneSlot), NOT the crosslinker two-pass. So the gather REUSES the
 * CrossBridge CSR-inverse (csrHistogram/csrScan/csrScatter) keyed by attachNode + MiniFilamentSystem.
 * backboneGather VERBATIM (NodeStore.nodeData uses the same stride-6 reaction layout):
 *   1. tether (here): each child self-writes the rod-side force(+torque) into its own rod slot and stores
 *      the node-side reaction in nodeData[6*a..] (one writer/rod ⇒ race-free, no atomics).
 *   2. CrossBridge.csr{Histogram,Scan,Scatter} keyed by attachNode (REUSED VERBATIM).
 *   3. MiniFilamentSystem.backboneGather over nodeData → node.forceSum/torqueSum (REUSED VERBATIM).
 *
 * Gated bit-identical vs MiniFilamentSystem.bruteGather (a brute per-attachment sum), CPU≡GPU.
 *
 * The RADIAL attach point co-rotates with the node body frame (v1 xToX(node, myoPtsInx)):
 *   surface = node.coord + ru·node.uVec + ry·node.yVec + rz·node.zVec  (ru,ry,rz the body-frame offset).
 * Singlet vs dimer (the only faithful per-child differences, carried in attachCoeff/attachAtEnd1):
 *   SINGLET (atEnd1=0): force at the rod CENTER (no rod torque); node reaction at the node CENTER (no node torque).
 *   DIMER   (atEnd1=1): force at the rod END1 (R×F rod torque); node reaction at the surface point (node torque).
 * Torque arms are in METRES (offset µm · 1e-6), matching v1 Thing.incForceSum(F,pt) + DimerCouplingSystem.
 *
 * TornadoVM: the tether math is inlined in the top-level @Parallel kernel (no helper); race-free, no
 * KernelContext/atomics; runs identically on the GPU TaskGraph and the -cpu runner.
 */
public final class NodeSystem {
    private NodeSystem() {}

    /**
     * Per-attachment radial tether: rod (= 3·motor) end1 ↔ the node's body-frame surface point.
     * Self-writes the rod-side force(+torque) into the motor body; stores the node-side reaction into
     * nodeData[6*a..] for the gather. One thread per attachment; rod slots are uniquely owned ⇒ race-free.
     *
     * 15 args (TornadoVM task() cap): motor body coord/uVec/segLength/bTransGam/forceSum/torqueSum (6),
     *   node coord/uVec/yVec/invTransY (4 — zVec computed in-kernel as uVec×yVec, the orthonormal frame),
     *   attachKey (packed node|motor) / radial (planar X|Y|Z) / attachCoeffK (signed: >0 dimer-end1+torque,
     *   <0 singlet-center) / nodeData / nodeParams (5).
     */
    public static void tether(
            FloatArray mCoord, FloatArray mUVec, FloatArray mSegLength,
            FloatArray mBTransGam, FloatArray mForceSum, FloatArray mTorqueSum,
            FloatArray nCoord, FloatArray nUVec, FloatArray nYVec, FloatArray nInvTransY,
            IntArray attachKey, FloatArray radial, FloatArray attachCoeffK,
            FloatArray nodeData, FloatArray nodeParams) {

        int nMB = mCoord.getSize() / 3;     // motor body sub-bodies (= 3·nMotors)
        int nN  = nCoord.getSize() / 3;     // nodes
        int nA  = attachCoeffK.getSize();
        double dt = nodeParams.get(0);

        for (@Parallel int a = 0; a < nA; a++) {
            int k = attachKey.get(a);
            int rod = 3 * attachKey.get(nA + a);
            double cs = attachCoeffK.get(a);
            boolean atEnd1 = cs > 0.0;
            double coeff = atEnd1 ? cs : -cs;     // magnitude (sign carried atEnd1)

            // node pose: orthonormal body frame (uVec, yVec, zVec=uVec×yVec) + the radial surface attach point
            double ncx = nCoord.get(k), ncy = nCoord.get(nN + k), ncz = nCoord.get(2 * nN + k);
            double nux = nUVec.get(k), nuy = nUVec.get(nN + k), nuz = nUVec.get(2 * nN + k);
            double nyx = nYVec.get(k), nyy = nYVec.get(nN + k), nyz = nYVec.get(2 * nN + k);
            double nzx = nuy * nyz - nuz * nyy, nzy = nuz * nyx - nux * nyz, nzz = nux * nyy - nuy * nyx;
            double ru = radial.get(a), ry = radial.get(nA + a), rz = radial.get(2 * nA + a);
            double pax = ncx + ru * nux + ry * nyx + rz * nzx;
            double pay = ncy + ru * nuy + ry * nyy + rz * nzy;
            double paz = ncz + ru * nuz + ry * nyz + rz * nzz;

            // rod pose + end1 (= coord − ½·len·uVec)
            double rcx = mCoord.get(rod), rcy = mCoord.get(nMB + rod), rcz = mCoord.get(2 * nMB + rod);
            double rux = mUVec.get(rod),  ruy = mUVec.get(nMB + rod),  ruz = mUVec.get(2 * nMB + rod);
            double rlen = mSegLength.get(rod);
            double re1x = rcx - 0.5 * rlen * rux, re1y = rcy - 0.5 * rlen * ruy, re1z = rcz - 0.5 * rlen * ruz;

            double fnx = 0, fny = 0, fnz = 0, tnx = 0, tny = 0, tnz = 0;   // node-side reaction

            // ---- tether spring: F_rod = forceMag·l (toward surface), F_node = −F_rod ----
            double dx = pax - re1x, dy = pay - re1y, dz = paz - re1z;      // v1 linkUVec1 = unit(surface − rodEnd1)
            double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (strain > 0.0) {
                double inv = 1.0 / strain;
                double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                double denom = dt * (1.0 / mBTransGam.get(nMB + rod) + nInvTransY.get(k));   // translational drags
                double forceMag = (denom > 0.0) ? (coeff * 1.0e-6 * strain / denom) : 0.0;
                double Fx = forceMag * lx, Fy = forceMag * ly, Fz = forceMag * lz;
                // rod side: central force always
                mForceSum.set(rod,            (float) (mForceSum.get(rod)            + Fx));
                mForceSum.set(nMB + rod,      (float) (mForceSum.get(nMB + rod)      + Fy));
                mForceSum.set(2 * nMB + rod,  (float) (mForceSum.get(2 * nMB + rod)  + Fz));
                if (atEnd1) {
                    // rod torque from applying F at end1: R = (end1 − center)·1e-6 = −0.5·rlen·uVec·1e-6
                    double Rx = -0.5 * rlen * rux * 1.0e-6, Ry = -0.5 * rlen * ruy * 1.0e-6, Rz = -0.5 * rlen * ruz * 1.0e-6;
                    mTorqueSum.set(rod,           (float) (mTorqueSum.get(rod)           + (Ry * Fz - Rz * Fy)));
                    mTorqueSum.set(nMB + rod,     (float) (mTorqueSum.get(nMB + rod)     + (Rz * Fx - Rx * Fz)));
                    mTorqueSum.set(2 * nMB + rod, (float) (mTorqueSum.get(2 * nMB + rod) + (Rx * Fy - Ry * Fx)));
                }
                // node-side reaction: −F (at the node center for singlets; at the surface point for dimers)
                fnx = -Fx; fny = -Fy; fnz = -Fz;
                if (atEnd1) {
                    // node torque from applying −F at the surface point: R = (surface − nodeCenter)·1e-6 = (ru,ry,rz)·1e-6
                    double Rnx = ru * 1.0e-6, Rny = ry * 1.0e-6, Rnz = rz * 1.0e-6;
                    tnx = Rny * fnz - Rnz * fny;
                    tny = Rnz * fnx - Rnx * fnz;
                    tnz = Rnx * fny - Rny * fnx;
                }
            }

            int o = NodeStore.NODE_STRIDE * a;
            nodeData.set(o,     (float) fnx); nodeData.set(o + 1, (float) fny); nodeData.set(o + 2, (float) fnz);
            nodeData.set(o + 3, (float) tnx); nodeData.set(o + 4, (float) tny); nodeData.set(o + 5, (float) tnz);
        }
    }

    /**
     * EXPERIMENT scheme 1 — DIRECT LOAD INJECTION (physics deviation from v1, authorized; default OFF).
     * Treat a CROSS-CAPTURED node myosin as a rigid lever from the node to the filament: route its cross-bridge
     * HEAD force straight onto the NODE body (at the surface attach point, with torque), instead of letting it
     * accelerate the myosin into a soft-tether creep. A FORCE (not a spring) ⇒ adds NO stiffness; only the ~few
     * bound myosins contribute. Conservation: the head force lands on the node here + the segment (segGather, as
     * now); it is NOT applied to the motor body (the caller SKIPS applyHeadForce under this scheme) ⇒ counted once.
     * ADDS to nodeData[6a..] (the tether wrote it first); the existing backboneGather sums it onto the node.
     * Parallel over attachments (one writer per a) ⇒ race-free, no atomics. Dimer: both heads (m, m+1).
     */
    public static void xbridgeInject(
            FloatArray bondData,
            FloatArray nCoord, FloatArray nUVec, FloatArray nYVec,
            IntArray attachKey, FloatArray radial, FloatArray attachCoeffK,
            IntArray boundSeg, FloatArray nodeData, FloatArray nodeParams) {
        int nN = nCoord.getSize() / 3;
        int nA = attachCoeffK.getSize();
        int ST = CrossBridgeSystem.STRIDE;
        for (@Parallel int a = 0; a < nA; a++) {
            int k = attachKey.get(a);
            int m = attachKey.get(nA + a);            // tethered motor slot (singlet, or dimer myo1)
            boolean dimer = attachCoeffK.get(a) > 0.0;
            // node body frame + surface offset R = (surface − center), µm
            double nux = nUVec.get(k), nuy = nUVec.get(nN + k), nuz = nUVec.get(2 * nN + k);
            double nyx = nYVec.get(k), nyy = nYVec.get(nN + k), nyz = nYVec.get(2 * nN + k);
            double nzx = nuy * nyz - nuz * nyy, nzy = nuz * nyx - nux * nyz, nzz = nux * nyy - nuy * nyx;
            double ru = radial.get(a), ry = radial.get(nA + a), rz = radial.get(2 * nA + a);
            double Rx = ru * nux + ry * nyx + rz * nzx;
            double Ry = ru * nuy + ry * nyy + rz * nzy;
            double Rz = ru * nuz + ry * nyz + rz * nzz;
            // sum the bound heads' cross-bridge force (rigid lever ⇒ node feels +F, toward the filament)
            double fx = 0, fy = 0, fz = 0;
            if (boundSeg.get(m) >= 0) { fx += bondData.get(m * ST); fy += bondData.get(m * ST + 1); fz += bondData.get(m * ST + 2); }
            if (dimer && boundSeg.get(m + 1) >= 0) { fx += bondData.get((m + 1) * ST); fy += bondData.get((m + 1) * ST + 1); fz += bondData.get((m + 1) * ST + 2); }
            double Rmx = Rx * 1.0e-6, Rmy = Ry * 1.0e-6, Rmz = Rz * 1.0e-6;
            double Tx = Rmy * fz - Rmz * fy, Ty = Rmz * fx - Rmx * fz, Tz = Rmx * fy - Rmy * fx;
            int o = NodeStore.NODE_STRIDE * a;
            nodeData.set(o,     (float) (nodeData.get(o)     + fx));
            nodeData.set(o + 1, (float) (nodeData.get(o + 1) + fy));
            nodeData.set(o + 2, (float) (nodeData.get(o + 2) + fz));
            nodeData.set(o + 3, (float) (nodeData.get(o + 3) + Tx));
            nodeData.set(o + 4, (float) (nodeData.get(o + 4) + Ty));
            nodeData.set(o + 5, (float) (nodeData.get(o + 5) + Tz));
        }
    }

    /**
     * EXPERIMENT scheme 2 — STATE-DEPENDENT STIFF TETHER (default OFF). Identical to tether(), EXCEPT a
     * cross-captured myosin (boundSeg≥0) uses a stiffer load coeff `boundCoeff` while bound; the ~many unbound
     * keep their soft `1/N` retention coeff. Aggregate node spring stiffness scales with the FEW captured ⇒ the
     * dt-stability stress is the capture count, not 120. (boundCoeff in nodeParams[1].) */
    public static void tetherBoundStiffen(
            FloatArray mCoord, FloatArray mUVec, FloatArray mSegLength,
            FloatArray mBTransGam, FloatArray mForceSum, FloatArray mTorqueSum,
            FloatArray nCoord, FloatArray nUVec, FloatArray nYVec, FloatArray nInvTransY,
            IntArray attachKey, FloatArray radial, FloatArray attachCoeffK,
            IntArray boundSeg, FloatArray nodeData, FloatArray nodeParams) {
        int nMB = mCoord.getSize() / 3;
        int nN  = nCoord.getSize() / 3;
        int nA  = attachCoeffK.getSize();
        double dt = nodeParams.get(0);
        double boundCoeff = nodeParams.get(1);
        for (@Parallel int a = 0; a < nA; a++) {
            int k = attachKey.get(a);
            int mtr = attachKey.get(nA + a);
            int rod = 3 * mtr;
            double cs = attachCoeffK.get(a);
            boolean atEnd1 = cs > 0.0;
            double coeff = atEnd1 ? cs : -cs;
            if (boundSeg.get(mtr) >= 0) coeff = boundCoeff;   // bound ⇒ stiff load coeff (the only change vs tether)
            double ncx = nCoord.get(k), ncy = nCoord.get(nN + k), ncz = nCoord.get(2 * nN + k);
            double nux = nUVec.get(k), nuy = nUVec.get(nN + k), nuz = nUVec.get(2 * nN + k);
            double nyx = nYVec.get(k), nyy = nYVec.get(nN + k), nyz = nYVec.get(2 * nN + k);
            double nzx = nuy * nyz - nuz * nyy, nzy = nuz * nyx - nux * nyz, nzz = nux * nyy - nuy * nyx;
            double ru = radial.get(a), ry = radial.get(nA + a), rz = radial.get(2 * nA + a);
            double pax = ncx + ru * nux + ry * nyx + rz * nzx;
            double pay = ncy + ru * nuy + ry * nyy + rz * nzy;
            double paz = ncz + ru * nuz + ry * nyz + rz * nzz;
            double rcx = mCoord.get(rod), rcy = mCoord.get(nMB + rod), rcz = mCoord.get(2 * nMB + rod);
            double rux = mUVec.get(rod),  ruy = mUVec.get(nMB + rod),  ruz = mUVec.get(2 * nMB + rod);
            double rlen = mSegLength.get(rod);
            double re1x = rcx - 0.5 * rlen * rux, re1y = rcy - 0.5 * rlen * ruy, re1z = rcz - 0.5 * rlen * ruz;
            double fnx = 0, fny = 0, fnz = 0, tnx = 0, tny = 0, tnz = 0;
            double dx = pax - re1x, dy = pay - re1y, dz = paz - re1z;
            double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (strain > 0.0) {
                double inv = 1.0 / strain;
                double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                double denom = dt * (1.0 / mBTransGam.get(nMB + rod) + nInvTransY.get(k));
                double forceMag = (denom > 0.0) ? (coeff * 1.0e-6 * strain / denom) : 0.0;
                double Fx = forceMag * lx, Fy = forceMag * ly, Fz = forceMag * lz;
                mForceSum.set(rod,           (float) (mForceSum.get(rod)           + Fx));
                mForceSum.set(nMB + rod,     (float) (mForceSum.get(nMB + rod)     + Fy));
                mForceSum.set(2 * nMB + rod, (float) (mForceSum.get(2 * nMB + rod) + Fz));
                if (atEnd1) {
                    double Rx = -0.5 * rlen * rux * 1.0e-6, Ry = -0.5 * rlen * ruy * 1.0e-6, Rz = -0.5 * rlen * ruz * 1.0e-6;
                    mTorqueSum.set(rod,           (float) (mTorqueSum.get(rod)           + (Ry * Fz - Rz * Fy)));
                    mTorqueSum.set(nMB + rod,     (float) (mTorqueSum.get(nMB + rod)     + (Rz * Fx - Rx * Fz)));
                    mTorqueSum.set(2 * nMB + rod, (float) (mTorqueSum.get(2 * nMB + rod) + (Rx * Fy - Ry * Fx)));
                }
                fnx = -Fx; fny = -Fy; fnz = -Fz;
                if (atEnd1) {
                    double Rnx = ru * 1.0e-6, Rny = ry * 1.0e-6, Rnz = rz * 1.0e-6;
                    tnx = Rny * fnz - Rnz * fny; tny = Rnz * fnx - Rnx * fnz; tnz = Rnx * fny - Rny * fnx;
                }
            }
            int o = NodeStore.NODE_STRIDE * a;
            nodeData.set(o,     (float) fnx); nodeData.set(o + 1, (float) fny); nodeData.set(o + 2, (float) fnz);
            nodeData.set(o + 3, (float) tnx); nodeData.set(o + 4, (float) tny); nodeData.set(o + 5, (float) tnz);
        }
    }
}
