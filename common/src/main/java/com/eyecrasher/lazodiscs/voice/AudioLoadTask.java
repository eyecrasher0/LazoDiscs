package com.eyecrasher.lazodiscs.voice;

import java.util.concurrent.FutureTask;

/** Cancels queued work without interrupting a running mod class/archive reader. */
public final class AudioLoadTask extends FutureTask<Void> {
    private volatile boolean exited;

    public AudioLoadTask(Runnable task) { super(task, null); }

    @Override public void run() {
        try { super.run(); }
        finally { exited = true; }
    }

    /** Unlike isDone(), cancellation alone does not claim the running worker has returned. */
    public boolean hasExited() { return exited; }

    @Override public boolean cancel(boolean mayInterruptIfRunning) {
        // Forge's shared SecureJarHandler channel can close if class loading is
        // interrupted. Callers discard late results; network work has timeouts.
        return super.cancel(false);
    }
}
