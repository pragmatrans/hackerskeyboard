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
    /** Whole families win once the previous word names them: "git <tab>" should be git verbs. */
    private static final int GROUP_BOOST = 3000;

    /** Index in this array is the group id stored per token; 0 means "no family". */
    private static final String[] GROUPS = {"", "git", "docker", "apt", "systemd", "net", "py"};

    private final Context mContext;
    private final LatinIME mIme;
    private String[] mWords = new String[0];
    private int[] mFreq = new int[0];
    private int[] mGroup = new int[0];
    private volatile boolean mLoaded;

    public CmdDictionary(LatinIME ime) {
        mIme = ime;
        mContext = ime.getApplicationContext();
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
            final List<Integer> groups = new ArrayList<Integer>();
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] parts = line.split("\t");
                if (parts.length < 2 || parts[0].length() == 0) {
                    continue;
                }
                words.add(parts[0]);
                try {
                    freqs.add(Integer.valueOf(parts[1].trim()));
                } catch (NumberFormatException bad) {
                    freqs.add(Integer.valueOf(0));
                }
                groups.add(Integer.valueOf(parts.length > 2 ? groupId(parts[2].trim()) : 0));
            }
            reader.close();
            final String[] loaded = words.toArray(new String[words.size()]);
            final int[] loadedFreq = new int[loaded.length];
            final int[] loadedGroup = new int[loaded.length];
            for (int i = 0; i < loaded.length; i++) {
                loadedFreq[i] = freqs.get(i).intValue();
                loadedGroup[i] = groups.get(i).intValue();
            }
            mWords = loaded;
            mFreq = loadedFreq;
            mGroup = loadedGroup;
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
        final String previous = mIme == null ? null : mIme.getPreviousWord();
        final int wantedGroup = previous == null ? 0 : triggerGroup(previous);
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
            final int weight = mFreq[i] + (wantedGroup != 0 && mGroup[i] == wantedGroup
                    ? GROUP_BOOST : 0);
            insert(bestWords, bestFreq, word, weight, max);
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

    private static int groupId(String name) {
        for (int i = 1; i < GROUPS.length; i++) {
            if (GROUPS[i].equals(name)) {
                return i;
            }
        }
        return 0;
    }

    /** Maps the word before the cursor to a family, so its members are ranked first. */
    private static int triggerGroup(String previous) {
        if (previous.length() >= 3 && previous.startsWith("git")) {
            return groupId("git");
        }
        if (previous.startsWith("docker") || previous.equals("compose")) {
            return groupId("docker");
        }
        if (previous.startsWith("apt") || previous.equals("pkg") || previous.equals("dpkg")) {
            return groupId("apt");
        }
        if (previous.equals("systemctl") || previous.equals("service")
                || previous.equals("journalctl")) {
            return groupId("systemd");
        }
        if (previous.equals("ssh") || previous.equals("curl") || previous.equals("wget")
                || previous.equals("nmap") || previous.equals("ping") || previous.equals("rsync")
                || previous.equals("scp") || previous.equals("dig")) {
            return groupId("net");
        }
        if (previous.equals("python") || previous.equals("python3") || previous.equals("pip")
                || previous.equals("pip3") || previous.equals("npm")) {
            return groupId("py");
        }
        return 0;
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
