/*
 * Copyright (C) 2026 The Hacker's Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/**
 * Keeps the last crash where the settings screen can show it.
 *
 * Without a computer and adb there is no logcat, so an unexplained "app keeps stopping"
 * would otherwise be undebuggable: the trace is written to the app's own storage and
 * surfaced as a preference.
 */
public class CrashLog {
    private static final String TAG = "CrashLog";
    private static final String FILE = "last_crash.txt";
    private static final int MAX_CHARS = 4000;
    private static boolean sInstalled;

    public static void install(Context context) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                write(app, thread, error);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    private static void write(Context app, Thread thread, Throwable error) {
        try {
            final StringWriter body = new StringWriter();
            body.write(new Date().toString());
            body.write(", thread=");
            body.write(thread.getName());
            body.write(", heap=");
            final Runtime rt = Runtime.getRuntime();
            body.write((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024) + "/"
                    + rt.maxMemory() / (1024 * 1024) + " MB");
            body.write('\n');
            if (error instanceof OutOfMemoryError) {
                body.write("НЕ ВИСТАЧИЛО ПАМ'ЯТІ\n");
            }
            error.printStackTrace(new PrintWriter(body));
            final FileOutputStream out = app.openFileOutput(FILE, Context.MODE_PRIVATE);
            try {
                out.write(body.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            Log.w(TAG, "could not record the crash", ignored);
        }
    }

    /** @return the recorded trace, or a readable "nothing yet" line. */
    public static String last(Context context) {
        try {
            final FileInputStream in = context.openFileInput(FILE);
            try {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                final byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                String text = new String(buffer.toByteArray(), "UTF-8").trim();
                if (text.length() > MAX_CHARS) {
                    text = text.substring(0, MAX_CHARS) + "…";
                }
                return text;
            } finally {
                in.close();
            }
        } catch (Throwable none) {
            return "збоїв не було";
        }
    }

    public static void clear(Context context) {
        context.deleteFile(FILE);
    }
}
