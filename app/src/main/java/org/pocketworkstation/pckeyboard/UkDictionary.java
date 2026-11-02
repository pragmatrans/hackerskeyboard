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
    /** Last word the user typed that the list did not recognise - prefills the editor. */
    private static volatile String sLastUnknown = "";

    static String lastUnknown() {
        return sLastUnknown;
    }
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
    /**
     * Every word lives in one char array with an offset table, not in 400k separate Strings.
     * Each String costs ~40 bytes of object and array headers on top of its characters, which
     * is ~16 MB of pure overhead at this size - enough to push a phone into an OOM crash loop
     * when the keyboard is restarted a few times.
     */
    private char[] mChars = new char[0];
    private int[] mOffsets = new int[]{0};
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
            final byte[] raw = readAsset(context);
            // Two exact counts up front: allocating by guesswork means a doubling copy of a
            // multi-megabyte char array, which is exactly the spike we are trying to avoid.
            int charCount = 0, lineCount = 0;
            for (int k = 0; k < raw.length; k++) {
                final byte v = raw[k];
                if ((v & 0xC0) != 0x80) {
                    charCount++;
                }
                if (v == '\n') {
                    lineCount++;
                }
            }
            final Builder b = new Builder(charCount, lineCount);
            int i = 0;
            boolean sorted = true;
            while (i < raw.length) {
                final int wordStart = b.charsLength();
                int ch = 0;
                while (i < raw.length) {
                    ch = decodeUtf8(raw, i);
                    i += utf8Length(raw[i]);
                    if (ch == '\t' || ch == '\n' || ch == '\r') {
                        break;
                    }
                    b.addChar((char) ch);
                }
                if (ch != '\t') {
                    b.truncateTo(wordStart);   // a line without a frequency: drop it
                    if (ch == '\r' && i < raw.length && raw[i] == '\n') {
                        i++;
                    }
                    continue;
                }
                int freq = 0;
                boolean sawDigit = false;
                while (i < raw.length) {
                    ch = decodeUtf8(raw, i);
                    i += utf8Length(raw[i]);
                    if (ch < '0' || ch > '9') {
                        break;
                    }
                    freq = freq * 10 + (ch - '0');
                    sawDigit = true;
                }
                if (!sawDigit) {
                    b.truncateTo(wordStart);
                    continue;
                }
                b.endWord();
                if (b.wordCount() > 1
                        && compareWords(b, b.wordCount() - 2, b.wordCount() - 1) > 0) {
                    sorted = false;
                }
                b.addFreq(freq);
            }
            if (!sorted) {
                sState = "ПОМИЛКА: список слів не відсортований";
                Log.e(TAG, "word list is not sorted, refusing to use it");
                return;
            }
            b.trim();
            mChars = b.chars;
            mOffsets = b.offsets;
            mFreq = b.freqs;
            mLoaded = true;
            sState = "завантажено " + wordCount() + " слів за "
                    + (System.currentTimeMillis() - start) + " мс";
        } catch (Throwable t) {
            Log.e(TAG, "could not load " + ASSET, t);
            sState = "ПОМИЛКА: " + t;
        }
        Log.i(TAG, "loaded " + wordCount() + " words in "
                + (System.currentTimeMillis() - start) + "ms");
    }

    private static byte[] readAsset(Context context) throws java.io.IOException {
        final java.io.InputStream in = context.getAssets().open(ASSET);
        try {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 21);
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static int utf8Length(byte first) {
        if ((first & 0x80) == 0) {
            return 1;
        }
        if ((first & 0xE0) == 0xC0) {
            return 2;
        }
        return 3;
    }

    private static int decodeUtf8(byte[] raw, int i) {
        final int b0 = raw[i] & 0xFF;
        if (b0 < 0x80) {
            return b0;
        }
        if ((b0 & 0xE0) == 0xC0 && i + 1 < raw.length) {
            return ((b0 & 0x1F) << 6) | (raw[i + 1] & 0x3F);
        }
        if (i + 2 < raw.length) {
            return ((b0 & 0x0F) << 12) | ((raw[i + 1] & 0x3F) << 6) | (raw[i + 2] & 0x3F);
        }
        return b0;
    }

    /** Growable staging area: the asset is read once, without a String per line. */
    private static class Builder {
        char[] chars;
        int charsLen;
        int[] offsets;
        int[] freqs;
        int count;

        Builder(int charCapacity, int wordCapacity) {
            chars = new char[charCapacity];
            offsets = new int[wordCapacity + 1];
            freqs = new int[Math.max(1, wordCapacity)];
            offsets[0] = 0;
        }

        void addChar(char c) {
            if (charsLen == chars.length) {
                chars = java.util.Arrays.copyOf(chars, chars.length * 2);
            }
            chars[charsLen++] = c;
        }

        int charsLength() {
            return charsLen;
        }

        void truncateTo(int length) {
            charsLen = length;
        }

        void endWord() {
            if (count + 1 == offsets.length) {
                offsets = java.util.Arrays.copyOf(offsets, offsets.length * 2);
            }
            offsets[++count] = charsLen;
        }

        void addFreq(int freq) {
            if (count >= freqs.length) {
                freqs = java.util.Arrays.copyOf(freqs, Math.max(16, freqs.length * 2));
            }
            freqs[count - 1] = freq;
        }

        int wordCount() {
            return count;
        }

        /** Drops the spare capacity so the live arrays are exactly as long as the data. */
        void trim() {
            chars = java.util.Arrays.copyOf(chars, charsLen);
            offsets = java.util.Arrays.copyOf(offsets, count + 1);
            freqs = java.util.Arrays.copyOf(freqs, count);
        }
    }

    private static int compareWords(Builder b, int first, int second) {
        int p = b.offsets[first], end = b.offsets[first + 1];
        int q = b.offsets[second], end2 = b.offsets[second + 1];
        final int n = Math.min(end - p, end2 - q);
        for (int k = 0; k < n; k++) {
            final char a = b.chars[p + k], c = b.chars[q + k];
            if (a != c) {
                return a < c ? -1 : 1;
            }
        }
        return (end - p) - (end2 - q);
    }

    private int wordCount() {
        return Math.max(0, mOffsets.length - 1);
    }

    private String wordAt(int index) {
        return new String(mChars, mOffsets[index], mOffsets[index + 1] - mOffsets[index]);
    }

    /** Compares the stored word at {@code index} with the typed one, same order as compareTo. */
    private int compareWord(int index, String typed) {
        int p = mOffsets[index];
        final int end = mOffsets[index + 1];
        final int n = end - p;
        final int m = Math.min(n, typed.length());
        for (int k = 0; k < m; k++) {
            final char a = mChars[p + k], c = typed.charAt(k);
            if (a != c) {
                return a < c ? -1 : 1;
            }
        }
        return n - typed.length();
    }

    private int compareWord(int index, char[] typed, int length) {
        int p = mOffsets[index];
        final int end = mOffsets[index + 1];
        final int n = end - p;
        final int m = Math.min(n, length);
        for (int k = 0; k < m; k++) {
            final char a = mChars[p + k], c = typed[k];
            if (a != c) {
                return a < c ? -1 : 1;
            }
        }
        return n - length;
    }

    /** @return index of {@code word}, or a negative value when absent. */
    private int findWord(String word) {
        int lo = 0, hi = wordCount() - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int c = compareWord(mid, word);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private int findWord(char[] word, int length) {
        int lo = 0, hi = wordCount() - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int c = compareWord(mid, word, length);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
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
        return mLoaded && findWord(lower) >= 0;
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

        final int known = findWord(lower);
        if (length >= 3 && known < 0) {
            sLastUnknown = lower;
        }
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
        final int index = findWord(chars, chars.length);
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
