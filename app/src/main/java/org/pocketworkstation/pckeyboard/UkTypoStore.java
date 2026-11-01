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
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Corrections the user taught by hand: "when I type this, I meant that".
 *
 * The offline word list can only guess, so anything it keeps getting wrong goes here. Stored as
 * plain string pairs in SharedPreferences - a few hundred entries at most, and they must survive
 * reboots without another database.
 */
public class UkTypoStore {
    private static final String PREFS = "uk_corrections";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return the taught replacement for {@code wrong}, or null when there is none. */
    public static String correct(Context context, String wrong) {
        if (wrong == null || wrong.length() == 0) {
            return null;
        }
        return prefs(context).getString(wrong.toLowerCase(), null);
    }

    public static void put(Context context, String wrong, String right) {
        prefs(context).edit()
                .putString(wrong.trim().toLowerCase(), right.trim())
                .commit();
    }

    public static void remove(Context context, String wrong) {
        prefs(context).edit().remove(wrong.trim().toLowerCase()).commit();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    public static int size(Context context) {
        return prefs(context).getAll().size();
    }

    public static Map<String, ?> all(Context context) {
        return prefs(context).getAll();
    }
}
