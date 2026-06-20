package softbox;

/**
 * Increment 6c B2 — the implicit actin monomer pool, behind ONE accessor (recon §2c seam #2).
 *
 * v1's actin source is `Env.actinConc` — a **depletable scalar** (default 15 µM), NOT a spatial field:
 * `Crucible.takeMonomer(n)` subtracts `n·microMolarChangePerMonomer` from the scalar, mass-action growth
 * reads `[actin]` (`Crucible.java:164-184`). The node nucleation path is **[actin]-INDEPENDENT** (a fixed
 * per-node rate `kNodeNuc`, recon §2a), so for B2 the pool is pure **depletion bookkeeping**: each seed
 * consumes `actinSeed` monomers, and `available()` lets the emitter stop when the pool runs dry.
 *
 * SEAM #2 (flag only — do NOT build the field now): the nucleation reads/depletes the pool through the
 * `available()` / `take()` / `put()` accessor here. Today it is a scalar; later it becomes a depletable counter
 * and eventually a diffusing scalar field — WITHOUT rewiring the nucleation/turnover functions (they only ever
 * call these methods). This class is the single point that would change.
 *
 * µM-per-monomer (v1 Chamber.java:52): microMolarChangePerMonomer = (1e5^3·1e6)/(boxVolume·Avogadro),
 * boxVolume in µm³. Reproduced here so the depletion magnitude matches v1 for a given chamber volume.
 *
 * CONSERVATION LEDGER (inc 7 Stage 0). The pool is the µM scalar (seam #2 unchanged), but turnover needs an
 * EXACT conservation check (`pool_monomers + Σ monomerCount = const`). The µM scalar is a double ⇒ float drift.
 * So the pool also keeps an INTEGER ledger (totalTaken / totalReturned — every take/put is an integer monomer
 * count), letting the harness assert conservation in exact integer monomer units, independent of the µM scalar.
 * The ledger is bookkeeping only; it does NOT change the seam (the rate still reads conc()).
 */
public final class ActinPool {

    private double concMicroMolar;          // current [actin] (µM); the depletable scalar
    private final double uMPerMonomer;      // µM consumed per monomer (v1 microMolarChangePerMonomer)

    // ---- inc 7 Stage 0: exact integer conservation ledger (bookkeeping; does not touch the seam) ----
    private long totalTaken    = 0;         // cumulative monomers removed from the pool (growth/nucleation)
    private long totalReturned = 0;         // cumulative monomers returned to the pool (depoly + death)

    /** boxVolumeUm3 = the chamber volume (µm³); v1 Chamber.java:52 conversion. */
    public ActinPool(double initialConcMicroMolar, double boxVolumeUm3) {
        this.concMicroMolar = initialConcMicroMolar;
        this.uMPerMonomer = (1e5 * 1e5 * 1e5 * 1e6) / (boxVolumeUm3 * Constants.AvogadroNum);
    }

    /** Seam-#2 accessor: enough actin for `monomers` more? (the emitter's gate). */
    public boolean available(int monomers) { return concMicroMolar >= monomers * uMPerMonomer; }

    /** Seam-#2 accessor: deplete `monomers` worth of actin (v1 Crucible.takeMonomer). Floors at 0. */
    public void take(int monomers) {
        concMicroMolar -= monomers * uMPerMonomer;
        if (concMicroMolar < 0.0) concMicroMolar = 0.0;
        totalTaken += monomers;
    }

    /** Seam-#2 accessor (inc 7 Stage 0): RETURN `monomers` worth of actin to the pool (v1 Crucible.putMonomer).
     *  The depoly per-monomer return (1) and the death en-masse return (the dying segment's monomerCount). */
    public void put(int monomers) {
        concMicroMolar += monomers * uMPerMonomer;
        totalReturned += monomers;
    }

    public double conc() { return concMicroMolar; }
    public double uMPerMonomer() { return uMPerMonomer; }

    // ---- conservation ledger (exact integer monomer units) ----
    public long totalTaken()    { return totalTaken; }
    public long totalReturned() { return totalReturned; }
}
