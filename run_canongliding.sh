#!/bin/bash
# PHASE-2 Version-B canonical two-point motor in the LIVE THERMAL gliding assay (aorus, TornadoVM PTX).
# Wires bindCanonicalTwoPoint (the Version-B two-point binder: tip search + along-filament formability +
# immediate two-point collapse with a head snap) + bondForcesCanonical into GlidingHarness. Flag-gated
# (-canonical); the default (no-flag) path is byte-identical. Calibration (kOn + catch) is DEFERRED.
#   ./run_canongliding.sh -canondiag 12000        # Version-B native-binder characterization (two-F8 canonical)
#   ./run_canongliding.sh -canonical -gpu 6000    # two-F8 canonical GPU device-resident probe (CPU≡GPU)
#   ./run_canongliding.sh -config1diag 12000      # PHASE-2 Config 1 (PAIRS attachments + Hookean J1) characterization
#   ./run_canongliding.sh -config1 -gpu 6000      # Config-1 GPU device-resident probe (CPU≡GPU)
#   ./run_canongliding.sh -config1diag -dt 5e-6 12000   # dt-scaling of the Config-1 J1-strain tail
#   ./run_canongliding.sh -single -singlez -0.075 -kon 5e5 30000   # PHASE-2 step-3 single-molecule DUTY assay (kOn calibration)
#   ./run_canongliding.sh -config1 -kon 5e5 6000        # gliding ensemble at the calibrated kOn (transferability)
#   ./run_canongliding.sh -single -singlez -0.075 -kon 5e5 -tauavg 0.5 30000   # step-4a: time-averaged catch (t_on recovery)
#   ./run_canongliding.sh -single -kon 5e5 -tauavg 0.5 -fext -2 20000           # step-4a force-response guard
#   ./run_canongliding.sh -single -kon 5e5 -acorr 30000                         # step-4a τ_thermal autocorrelation
#   ./run_canongliding.sh -dcalib -singlez -0.075 -kon 5e5 -xcatch 3.65 20000    # step-4b catch d-calibration (rate vs load → Veigel 2.7 nm)
#   ./run_canongliding.sh -config1 -kon 2e5 -tauavg 0.5 -xcatch 3.65 -density 4000 -gpu 8000   # step-4b transferability (speed-density)
#   ./run_canongliding.sh -config1 -3js threejs_config1 20000          # viewer
# (Just a convenience wrapper over GlidingHarness — equivalent to run_gliding.sh with -canonical/-canondiag.)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx6G \
     -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.GlidingHarness -v1box "$@"
