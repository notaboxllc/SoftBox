package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * 2026-10-26 diagnostic: does TornadoVM skip the FIRST_EXECUTION copy-in of a buffer whose FIRST-touching task only
 * WRITES it (part of it)? Task A writes x[0..n) only; task B reads x[n..2n) into y. If y comes back 0 instead of 7,
 * the untouched half of x was never uploaded.
 */
public final class TornadoWriteFirstProbe {
    public static void writeHalf(DoubleArray x, IntArray c) { int n = c.get(0); for (@Parallel int i = 0; i < n; i++) x.set(i, 1.0); }
    public static void readHalf(DoubleArray x, DoubleArray y, IntArray c) { int n = c.get(0); for (@Parallel int i = 0; i < n; i++) y.set(i, x.get(n + i)); }
    public static void readWrite(DoubleArray x, IntArray c) { int n = c.get(0); for (@Parallel int i = 0; i < n; i++) x.set(i, x.get(i) + 1.0); }

    public static void main(String[] a) {
        int n = 256;
        for (int variant = 0; variant < 2; variant++) {
            DoubleArray x = new DoubleArray(2 * n), y = new DoubleArray(n); x.init(7.0); y.init(-1.0);
            IntArray c = IntArray.fromElements(n);
            TaskGraph tg = new TaskGraph("wf" + variant).transferToDevice(DataTransferMode.FIRST_EXECUTION, x, y, c);
            if (variant == 0) tg.task("a", TornadoWriteFirstProbe::writeHalf, x, c);      // first touch = write-only
            else              tg.task("a", TornadoWriteFirstProbe::readWrite, x, c);      // first touch = read+write
            tg.task("b", TornadoWriteFirstProbe::readHalf, x, y, c).transferToHost(DataTransferMode.EVERY_EXECUTION, x, y);
            GridScheduler gs = new GridScheduler(); WorkerGrid w = new WorkerGrid1D(n); w.setLocalWork(64, 1, 1);
            gs.addWorkerGrid("wf" + variant + ".a", w); gs.addWorkerGrid("wf" + variant + ".b", w);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs).execute();
            System.out.printf("variant %d (%s first): y[0]=%.1f (expect 7)  x[n]=%.1f (expect 7)  x[0]=%.1f (expect %s)%n", variant,
                    variant == 0 ? "write-only" : "read+write", y.get(0), x.get(n), x.get(0), variant == 0 ? "1" : "8");
        }
    }
}
