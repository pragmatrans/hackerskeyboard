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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline Ukrainian dictionary and typo corrector.
 *
 * Corrections cover the slips this keyboard's users actually make: one letter hit on a
 * neighbouring key scores best, then any single substitution, then a transposition of two
 * adjacent letters. Candidates are ranked by corpus frequency, so "модеш" becomes "можеш"
 * and not some rare word that happens to be one key away as well.
 *
 * Word list: assets/uk_words.txt, "word<TAB>freq" lines sorted by word, freq = occurrences
 * per billion. Plain text, because aapt decompresses and renames *.gz assets during the
 * build. Sorted array instead of a HashMap: a few hundred thousand words cost ~20 MB less.
 */
public class UkDictionary extends Dictionary {
    private static final String TAG = "UkDictionary";
    private static final String ASSET = "uk_words.txt";

    /** Words rarer than this are never offered as corrections. */
    private static final int MIN_FREQ = 200;
    /**
     * Autocorrect may replace what the user typed, so the winner has to be a word that is
     * genuinely common. Rare winners stay in the suggestion strip only.
     */
    private static final int MIN_AUTOCORRECT_SCORE = 2000;
    private static final int MAX_CANDIDATES = 3;
    /** Corrections taught by the user always beat guesses from the word list. */
    private static final int TAUGHT_SCORE = 1 << 24;
    private static final int FAR_KEY_PENALTY = 5;
    private static final int TRANSPOSITION_PENALTY = 2;
    private static final int INSERTION_PENALTY = 3;

    /** ЙЦУКЕН rows, used both as the substitution alphabet and to derive neighbours. */
    private static final String[] ROWS = {
        "йцукенгшщзхї", "фівапролджє", "ячсмитьбю"
    };
    private static final String ALPHABET = "йцукенгшщзхїфівапролджєячсмитьбюґ'";
    private static final Map<Character, String> sNeighbours = buildNeighbours();

    /** Readable state for the settings screen: no logcat on a phone-only setup. */
    private static volatile String sState = "словник ще не створювався";
    private static volatile String sLastCorrection = "виправлень ще не було";
    /** Last few decisions, newest first: typed word, whether it was known, best candidate. */
    private static final String[] sEvents = new String[6];
    private static int sEventsSeen;

    private static synchronized void logEvent(String event) {
        sEvents[sEventsSeen % sEvents.length] = event;
        sEventsSeen++;
    }

    private static synchronized String events() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= Math.min(sEventsSeen, sEvents.length); i++) {
            final String e = sEvents[(sEventsSeen - i) % sEvents.length];
            if (e != null) {
                sb.append('\n').append(e);
            }
        }
        return sb.toString();
    }

    /** True while nothing has tried to load the list yet in this process. */
    static boolean needsProbe() {
        return sState.startsWith("словник ще не");
    }

    /** Loads the list just to report its state on the settings screen. */
    static void probe(Context context) {
        new UkDictionary(context);
    }

    static String status() {
        return sState + "\n" + sLastCorrection + events();
    }

    private final Context mContext;
    private String[] mWords = new String[0];
    private int[] mFreq = new int[0];
    private volatile boolean mLoaded;

    public UkDictionary(Context context) {
        final Context app = context.getApplicationContext();
        mContext = app;
        sState = "завантажую…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                load(app);
            }
        }, "uk-dictionary-load").start();
    }

    private static Map<Character, String> buildNeighbours() {
        Map<Character, String> map = new HashMap<Character, String>();
        for (int r = 0; r < ROWS.length; r++) {
            final String row = ROWS[r];
            for (int i = 0; i < row.length(); i++) {
                StringBuilder sb = new StringBuilder();
                if (i > 0) {
                    sb.append(row.charAt(i - 1));
                }
                if (i + 1 < row.length()) {
                    sb.append(row.charAt(i + 1));
                }
                for (int other = 0; other < ROWS.length; other++) {
                    if (other == r) {
                        continue;
                    }
                    final String adj = ROWS[other];
                    final int from = Math.max(0, i - 1);
                    final int to = Math.min(adj.length() - 1, i + 1);
                    for (int j = from; j <= to; j++) {
                        sb.append(adj.charAt(j));
                    }
                }
                map.put(row.charAt(i), sb.toString());
            }
        }
        return map;
    }

    private void load(Context context) {
        final long start = System.currentTimeMillis();
        try {
            final List<String> words = new ArrayList<String>(300000);
            final List<Integer> freqs = new ArrayList<Integer>(300000);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    context.getAssets().open(ASSET), "UTF-8"), 8192);
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    final int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    words.add(line.substring(0, tab));
                    freqs.add(Integer.parseInt(line.substring(tab + 1)));
                }
            } finally {
                in.close();
            }
            // The asset is sorted by word; sort only if it is not, so we skip 240k boxed
            // Integer allocations and a comparator pass at startup.
            boolean sorted = true;
            for (int i = 1; i < words.size(); i++) {
                if (words.get(i - 1).compareTo(words.get(i)) > 0) {
                    sorted = false;
                    break;
                }
            }
            final String[] sortedWords;
            final int[] sortedFreq;
            if (sorted) {
                sortedWords = words.toArray(new String[words.size()]);
                sortedFreq = new int[sortedWords.length];
                for (int i = 0; i < sortedFreq.length; i++) {
                    sortedFreq[i] = freqs.get(i);
                }
            } else {
                final Integer[] order = new Integer[words.size()];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                final List<String> w = words;
                Arrays.sort(order, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        return w.get(a).compareTo(w.get(b));
                    }
                });
                sortedWords = new String[order.length];
                sortedFreq = new int[order.length];
                for (int i = 0; i < order.length; i++) {
                    sortedWords[i] = words.get(order[i]);
                    sortedFreq[i] = freqs.get(order[i]);
                }
            }
            mWords = sortedWords;
            mFreq = sortedFreq;
            mLoaded = true;
            sState = "завантажено " + mWords.length + " слів за "
                    + (System.currentTimeMillis() - start) + " мс";
        } catch (Throwable t) {
            Log.e(TAG, "could not load " + ASSET, t);
            sState = "ПОМИЛКА: " + t;
        }
        Log.i(TAG, "loaded " + mWords.length + " words in "
                + (System.currentTimeMillis() - start) + "ms");
    }

    public boolean isLoaded() {
        return mLoaded;
    }

    @Override
    public boolean isValidWord(CharSequence word) {
        if (word == null) {
            return false;
        }
        final String lower = word.toString().toLowerCase();
        // A word the user marked as a typo counts as wrong even when it is in the word list:
        // otherwise "already valid" would protect it from the correction they asked for.
        if (UkTypoStore.correct(mContext, lower) != null) {
            return false;
        }
        return mLoaded && indexOf(lower) >= 0;
    }

    private int indexOf(String word) {
        return Arrays.binarySearch(mWords, word);
    }

    @Override
    public void getWords(final WordComposer composer, final WordCallback callback,
            int[] nextLettersFrequencies) {
        if (!mLoaded) {
            return;
        }
        final CharSequence typed = composer.getTypedWord();
        if (typed == null) {
            return;
        }
        final String lower = typed.toString().toLowerCase();
        final int length = lower.length();

        final String taught = UkTypoStore.correct(mContext, lower);
        if (taught != null) {
            logEvent("«" + lower + "» taught -> " + taught);
            callback.addWord(taught.toCharArray(), 0, taught.length(), TAUGHT_SCORE,
                    Suggest.DIC_USER, DataType.UNIGRAM);
            return;
        }

        final int known = indexOf(lower);
        if (length < 3 || length > 30 || known >= 0) {
            logEvent("«" + lower + "» len=" + length + " known=" + (known >= 0 ? "yes" : "no")
                    + " -> skip");
            return;
        }

        final List<Candidate> found = new ArrayList<Candidate>();
        final char[] chars = lower.toCharArray();

        for (int i = 0; i < length; i++) {
            final char original = chars[i];
            final String neighbours = sNeighbours.get(original);
            for (int j = 0; j < ALPHABET.length(); j++) {
                final char candidate = ALPHABET.charAt(j);
                if (candidate == original) {
                    continue;
                }
                chars[i] = candidate;
                final boolean nearKey = neighbours != null && neighbours.indexOf(candidate) >= 0;
                addIfKnown(found, chars, nearKey ? 1 : FAR_KEY_PENALTY);
            }
            chars[i] = original;
        }

        for (int i = 0; i + 1 < length; i++) {
            if (chars[i] == chars[i + 1]) {
                continue;
            }
            final char left = chars[i];
            chars[i] = chars[i + 1];
            chars[i + 1] = left;
            addIfKnown(found, chars, TRANSPOSITION_PENALTY);
            chars[i + 1] = chars[i];
            chars[i] = left;
        }

        // A dropped letter ("привт") is as common as a wrong one.
        final char[] longer = new char[length + 1];
        for (int i = 0; i <= length; i++) {
            System.arraycopy(chars, 0, longer, 0, i);
            System.arraycopy(chars, i, longer, i + 1, length - i);
            final String before = i > 0 ? sNeighbours.get(chars[i - 1]) : null;
            final String after = i < length ? sNeighbours.get(chars[i]) : null;
            for (int j = 0; j < ALPHABET.length(); j++) {
                final char candidate = ALPHABET.charAt(j);
                longer[i] = candidate;
                final boolean nearKey = (before != null && before.indexOf(candidate) >= 0)
                        || (after != null && after.indexOf(candidate) >= 0);
                addIfKnown(found, longer, nearKey ? 1 : INSERTION_PENALTY);
            }
        }

        Collections.sort(found, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return b.score - a.score;
            }
        });

        if (found.isEmpty()) {
            logEvent("«" + lower + "» known=no best=- -> skip");
            return;
        }
        if (found.get(0).score < MIN_AUTOCORRECT_SCORE) {
            logEvent("«" + lower + "» known=no best=" + new String(found.get(0).word)
                    + "/" + found.get(0).score + " -> weak");
            return;
        }

        sLastCorrection = "остання заміна: " + lower + " → " + new String(found.get(0).word);
        logEvent("«" + lower + "» known=no best=" + new String(found.get(0).word)
                + "/" + found.get(0).score + " emit=yes");

        for (int i = 0; i < found.size() && i < MAX_CANDIDATES; i++) {
            final Candidate candidate = found.get(i);
            if (!callback.addWord(candidate.word, 0, candidate.word.length, candidate.score,
                    Suggest.DIC_MAIN, DataType.UNIGRAM)) {
                return;
            }
        }
    }

    private void addIfKnown(List<Candidate> found, char[] chars, int penalty) {
        final int index = indexOf(new String(chars));
        if (index < 0) {
            return;
        }
        final int freq = mFreq[index];
        if (freq < MIN_FREQ) {
            return;
        }
        found.add(new Candidate(chars, freq / penalty));
    }

    private static class Candidate {
        final char[] word;
        final int score;

        Candidate(char[] word, int score) {
            this.word = new char[word.length];
            System.arraycopy(word, 0, this.word, 0, word.length);
            this.score = score;
        }
    }
}
