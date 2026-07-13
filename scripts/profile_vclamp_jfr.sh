#!/bin/bash
# JFR sampling/allocation profile for the canonical CPU velocity-clamp benchmark.
set -euo pipefail

cd "$(dirname "$0")/.."

dt=${DT:-2.5e-6}
steps=${STEPS:-8000}
seed=${SEED:-0}
velocity=${VELOCITY:-14}
density=${DENSITY:-2000}
matbox_nm=${MATBOX_NM:-50}
out_dir=${OUT_DIR:-RUN_LOGS/performance/profile}
mkdir -p "$out_dir"

recording="$out_dir/vclamp.jfr"
log="$out_dir/vclamp.log"
timing="$out_dir/vclamp.time"

JDK_JAVA_OPTIONS="-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true" \
  /usr/bin/time -v -o "$timing" ./scripts/run_gliding.sh \
    -matbox "$matbox_nm" -density "$density" -dt "$dt" -seed "$seed" -vclamp "$velocity" "$steps" \
    > "$log" 2>&1

jfr summary "$recording" > "$out_dir/jfr_summary.txt"
jfr view --width 180 hot-methods "$recording" > "$out_dir/hot_methods.txt"
jfr view --width 180 allocation-by-site "$recording" > "$out_dir/allocation_by_site.txt"
jfr print --events jdk.ExecutionSample --stack-depth 64 "$recording" > "$out_dir/execution_samples.txt"

# Attribute Foreign MemorySegment and small math-helper leaf samples back to their simulation subsystem.
awk 'BEGIN { RS="\n\n" }
  /jdk.ExecutionSample/ {
    n++
    if (/MotorJointSystem/) category="joints"
    else if (/RigidRodLangevinIntegrationSystem/) category="integrate"
    else if (/BrownianForceSystem/) category="brownian"
    else if (/BindingDetectionSystem.bruteReachable|BindingDetectionSystem.reachTestDistSq/) category="bruteReachable"
    else if (/BindingDetectionSystem.bindNearest/) category="bindNearest"
    else if (/DerivedGeometrySystem.orthogonalizeY/) category="orthogonalizeY"
    else if (/DerivedGeometrySystem.derive/) category="derive"
    else if (/NucleotideCycleSystem/) category="cycle"
    else if (/CrossBridgeSystem.bondForces/) category="bondForces"
    else if (/TailAnchorSystem/) category="anchor"
    else if (/MotorStore.publishHeadFromBody/) category="publishHead"
    else if (/CrossBridgeSystem.applyHeadForce/) category="applyHeadForce"
    else if (/ChainBendingForceSystem/) category="chain"
    else if (/CrossBridgeSystem.registerForceDot/) category="registerForce"
    else if (/CrossBridgeSystem.couple|CrossBridgeSystem.snapshot|CrossBridgeSystem.csr|CrossBridgeSystem.segGather/) category="implicitGather"
    else if (/GlidingHarness.runForceVelocity/) category="measurementOther"
    else category="startupOther"
    count[category]++
  }
  END {
    print "category\tsamples\tpercent"
    for (category in count) printf "%s\t%d\t%.2f\n", category, count[category], 100.0 * count[category] / n
    printf "total\t%d\t100.00\n", n
  }
' "$out_dir/execution_samples.txt" > "$out_dir/caller_hotspots.tsv"
grep '^FVROW ' "$log"
cat "$out_dir/hot_methods.txt"
echo "wrote $out_dir"
