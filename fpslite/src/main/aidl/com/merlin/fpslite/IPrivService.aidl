package com.merlin.fpslite;

// Runs inside the Shizuku UserService process (shell uid 2000, or root if Shizuku
// was started with root). One persistent process: no fork/exec per sample.
interface IPrivService {
    // Reserved transaction code required by Shizuku for UserService teardown.
    void destroy() = 16777114;

    void exit() = 1;

    // Calls IBinder.dump() on a system service directly (like `dumpsys <service> <args>`)
    // without spawning a process. Falls back to running dumpsys if the binder is unavailable.
    String dump(String service, in String[] args) = 2;

    // Reads a sysfs/procfs file through a cached, re-seeked file descriptor. null if unreadable.
    String readText(String path) = 3;

    // Batch version of readText; entries are null when unreadable.
    String[] readTexts(in String[] paths) = 4;

    String[] listDir(String path) = 5;

    // Runs a shell command (used only by the Device probe screen). Returns stdout+stderr.
    String exec(String cmd) = 6;

    // GPU frequency/load via a continuous perfetto ftrace session.
    boolean gpuStart() = 7;
    // [status(1 data, 0 waiting, -1 unavailable), avgActiveFreqKHz, busyPermille, lastFreqKHz, events]
    long[] gpuSample() = 8;
    void gpuStop() = 9;
    String gpuLog() = 10;

    int uid() = 11;
}
