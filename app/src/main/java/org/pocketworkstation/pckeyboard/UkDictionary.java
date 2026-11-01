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
 * Word list: assets/uk_words.txt, "word<TAB>freq" lines, freq = occurrences per billion.
 * Plain text, because aapt decompresses and renames *.gz assets during the build.
 */
public class UkDictionary extends Dictionary {
    private static final String TAG = "UkDictionary";
    private static final String ASSET = "uk_words.txt";

    /** Words rarer than this are never offered as corrections. */
    private static final int MIN_FREQ = 40;
    private static final int MAX_CANDIDATES = 3;
    private static final int FAR_KEY_PENALTY = 5;
    private static final int TRANSPOSITION_PENALTY = 2;

    /** ЙЦУКЕН rows, used both as the substitution alphabet and to derive neighbours. */
    private static final String[] ROWS = {
        "йцукенгшщзхї", "фівапролджє", "ячсмитьбю"
    };
    private static final String ALPHABET = "йцукенгшщзхїфівапролджєячсмитьбюґ'";
    private static final Map<Character, String> sNeighbours = buildNeighbours();

    private final Map<String, Integer> mWords = new HashMap<String, Integer>();
    private volatile boolean mLoaded;

    public UkDictionary(Context context) {
        final Context app = context.getApplicationContext();
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
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    context.getAssets().open(ASSET), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    final int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    mWords.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
                }
            } finally {
                in.close();
            }
            mLoaded = true;
        } catch (Exception e) {
            Log.e(TAG, "could not load " + ASSET, e);
        }
        Log.i(TAG, "loaded " + mWords.size() + " words in "
                + (System.currentTimeMillis() - start) + "ms");
    }

    public boolean isLoaded() {
        return mLoaded;
    }

    @Override
    public boolean isValidWord(CharSequence word) {
        return mLoaded && word != null
                && mWords.containsKey(word.toString().toLowerCase());
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
        if (length < 3 || length > 30 || mWords.containsKey(lower)) {
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

        Collections.sort(found, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return b.score - a.score;
            }
        });

        for (int i = 0; i < found.size() && i < MAX_CANDIDATES; i++) {
            final Candidate candidate = found.get(i);
            if (!callback.addWord(candidate.word, 0, candidate.word.length, candidate.score,
                    Suggest.DIC_MAIN, DataType.UNIGRAM)) {
                return;
            }
        }
    }

    private void addIfKnown(List<Candidate> found, char[] chars, int penalty) {
        final Integer freq = mWords.get(new String(chars));
        if (freq == null || freq < MIN_FREQ) {
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
