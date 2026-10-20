package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Safety net: every crash of the launcher is written down, and after 3 within 5 minutes Home starts in safe mode
 * (no Top Shelf, character, wallpaper or sky) so the TV always has a working home screen. A launcher cannot
 * re-enable the stock Google TV home by itself; that still takes ADB (tools/restore.sh). Safe mode ends by
 * itself once Home has run for a few minutes without crashing.
 */
final class Watchdog {

    private static final int CRASHES = 3;
    private static final long WINDOW_MS = 5 * 60_000L;
    /** Running this long without a crash clears the record. */
    static final long STABLE_MS = 3 * 60_000L;

    private Watchdog() {
    }

    private static File log(Context ctx) {
        return new File(ctx.getFilesDir(), "crashes.txt");
    }

    /** Records each uncaught crash before the process dies, then lets Android handle it as usual. */
    static void install(Context ctx) {
        final File file = log(ctx.getApplicationContext());
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Recorder) return;
        Thread.setDefaultUncaughtExceptionHandler(new Recorder(file, previous));
    }

    /** Three or more crashes in the last five minutes. */
    static boolean safeMode(Context ctx) {
        try {
            List<String> lines = Files.readAllLines(log(ctx).toPath(), StandardCharsets.UTF_8);
            long now = System.currentTimeMillis(), recent = 0;
            for (String l : lines) {
                try {
                    if (now - Long.parseLong(l.trim()) < WINDOW_MS) recent++;
                } catch (NumberFormatException ignored) {
                }
            }
            return recent >= CRASHES;
        } catch (Exception e) {
            return false;
        }
    }

    static void clear(Context ctx) {
        log(ctx).delete();
    }

    private static final class Recorder implements Thread.UncaughtExceptionHandler {
        private final File file;
        private final Thread.UncaughtExceptionHandler previous;

        Recorder(File file, Thread.UncaughtExceptionHandler previous) {
            this.file = file;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(System.currentTimeMillis() + "\n");
            } catch (Exception ignored) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        }
    }
}
