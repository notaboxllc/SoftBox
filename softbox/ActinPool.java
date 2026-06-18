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
 * `available()` / `take()` accessor here. Today it is a scalar; later it becomes a depletable counter and
 * eventually a diffusing scalar field — WITHOUT rewiring the nucleation function (it only ever calls these
 * two methods). This class is the single point that would change.
 *
 * µM-per-monomer (v1 Chamber.java:52): microMolarChangePerMonomer = (1e5^3·1e6)/(boxVolume·Avogadro),
 * boxVolume in µm³. Reproduced here so the depletion magnitude matches v1 for a given chamber volume.
 */
public final class ActinPool {

    private double concMicroMolar;          // current [actin] (µM); the depletable scalar
    private final double uMPerMonomer;      // µM consumed per monomer (v1 microMolarChangePerMonomer)

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
    }

    public double conc() { return concMicroMolar; }
    public double uMPerMonomer() { return uMPerMonomer; }
}
