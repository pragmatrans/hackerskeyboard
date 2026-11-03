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
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Completes shell commands: busybox applets, coreutils, git and docker subcommands, common paths.
 *
 * Nothing here tries to be clever - it is prefix matching only, and only for words typed in
 * ASCII. Suggestions from this list in the middle of Ukrainian text would be pure noise, which
 * is why both the switch and the character check exist.
 */
public class CmdDictionary {
    private static final String TAG = "CmdDictionary";
    private static final String ASSET = "cmd_words.txt";
    public static final String PREF_CMD_SUGGESTIONS = "cmd_suggestions";

    private static final int MIN_PREFIX = 2;
    private static final int MAX_TYPED_LENGTH = 40;
    private static final int MAX_PREFIX_WALK = 400;

    private final Context mContext;
    private String[] mWords = new String[0];
    private int[] mFreq = new int[0];
    private volatile boolean mLoaded;

    public CmdDictionary(Context context) {
        mContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                load();
            }
        }, "CmdDictionaryLoader").start();
    }

    private void load() {
        final long start = System.currentTimeMillis();
        try {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mContext.getAssets().open(ASSET), "UTF-8"));
            final List<String> words = new ArrayList<String>();
            final List<Integer> freqs = new ArrayList<Integer>();
            String line;
            while ((line = reader.readLine()) != null) {
                final int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                words.add(line.substring(0, tab));
                try {
                    freqs.add(Integer.valueOf(line.substring(tab + 1).trim()));
                } catch (NumberFormatException bad) {
                    freqs.add(Integer.valueOf(0));
                }
            }
            reader.close();
            final String[] loaded = words.toArray(new String[words.size()]);
            final int[] loadedFreq = new int[loaded.length];
            for (int i = 0; i < loaded.length; i++) {
                loadedFreq[i] = freqs.get(i).intValue();
            }
            mWords = loaded;
            mFreq = loadedFreq;
            mLoaded = true;
            Log.i(TAG, "loaded " + loaded.length + " commands in "
                    + (System.currentTimeMillis() - start) + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "could not load " + ASSET, t);
        }
    }

    public boolean isLoaded() {
        return mLoaded;
    }

    public boolean isEnabled() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        return prefs.getBoolean(PREF_CMD_SUGGESTIONS, false);
    }

    /**
     * Appends completions for {@code typed} to {@code out}.
     *
     * @return how many were added
     */
    public int getWords(CharSequence typed, List<CharSequence> out, int max) {
        if (!mLoaded || typed == null || !isEnabled()) {
            return 0;
        }
        final int length = typed.length();
        if (length < MIN_PREFIX || length > MAX_TYPED_LENGTH) {
            return 0;
        }
        for (int i = 0; i < length; i++) {
            final char c = typed.charAt(i);
            final boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '/';
            if (!allowed) {
                return 0;
            }
        }
        final String prefix = typed.toString().toLowerCase();
        final int start = lowerBound(prefix);
        if (start < 0) {
            return 0;
        }

        // Alphabetical neighbours are not the interesting ones: collect a window and keep the
        // most used of them, which is what a person actually reaches for.
        final String[] bestWords = new String[max];
        final int[] bestFreq = new int[max];
        int found = 0;
        for (int i = start; i < mWords.length && found < MAX_PREFIX_WALK; i++) {
            final String word = mWords[i];
            if (!word.startsWith(prefix)) {
                break;
            }
            found++;
            if (word.equals(prefix)) {
                continue;
            }
            insert(bestWords, bestFreq, word, mFreq[i], max);
        }

        int added = 0;
        for (int i = 0; i < max && bestWords[i] != null; i++) {
            if (out.size() >= max) {
                break;
            }
            if (!contains(out, bestWords[i])) {
                out.add(bestWords[i]);
                added++;
            }
        }
        return added;
    }

    private static void insert(String[] words, int[] freq, String word, int value, int max) {
        for (int i = 0; i < max; i++) {
            if (words[i] == null) {
                words[i] = word;
                freq[i] = value;
                return;
            }
            if (value > freq[i]) {
                for (int j = max - 1; j > i; j--) {
                    words[j] = words[j - 1];
                    freq[j] = freq[j - 1];
                }
                words[i] = word;
                freq[i] = value;
                return;
            }
        }
    }

    private static boolean contains(List<CharSequence> out, String word) {
        for (int i = 0, size = out.size(); i < size; i++) {
            if (word.contentEquals(out.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** First index whose word is not alphabetically before {@code prefix}. */
    private int lowerBound(String prefix) {
        int lo = 0, hi = mWords.length - 1, answer = -1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (mWords[mid].compareTo(prefix) >= 0) {
                answer = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return answer;
    }
}
