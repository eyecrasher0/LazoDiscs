package com.eyecrasher.lazodiscs.voice;

import java.util.concurrent.FutureTask;

/** Cancels queued work without interrupting a running mod class/archive reader. */
public final class AudioLoadTask extends FutureTask<Void> {
    public AudioLoadTask(Runnable task) { super(task, null); }

    @Override public boolean cancel(boolean mayInterruptIfRunning) {
        // Forge's shared SecureJarHandler channel can close if class loading is
        // interrupted. Callers discard late results; network work has timeouts.
        return super.cancel(false);
    }
}
