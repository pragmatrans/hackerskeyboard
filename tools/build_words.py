#!/usr/bin/env python3
"""Build the Ukrainian word/frequency asset for the keyboard.

Source: wordfreq (Apache-2.0) - corpus-derived frequencies. The hunspell filter was
dropped on purpose: it rejects real inflected and colloquial forms, and a missing form
means the keyboard "corrects" a word that was already right.

Output: assets/uk_words.txt, lines "word<TAB>freq" sorted by word (the keyboard binary
searches it), freq = occurrences per billion words (int), so it fits in int32.
Plain text on purpose: aapt silently decompresses and renames *.gz assets.

    python3 tools/build_words.py app/src/main/assets/uk_words.txt 1.5
"""
import re
import sys

from wordfreq import top_n_list, zipf_frequency

MIN_ZIPF = float(sys.argv[2]) if len(sys.argv) > 2 else 1.5
CYR = re.compile(r"^[а-яіїєґ]+(?:['’][а-яіїєґ]+)?$")

rows = []
for word in top_n_list("uk", 600000, wordlist="large"):
    if not CYR.match(word):
        continue
    z = zipf_frequency(word, "uk")
    if z < MIN_ZIPF:
        continue
    rows.append((word, int(10 ** z)))

rows.sort(key=lambda row: row[0])

with open(sys.argv[1], "w", encoding="utf-8") as f:
    for word, freq in rows:
        f.write(f"{word}\t{freq}\n")

print(f"kept={len(rows)} min_zipf={MIN_ZIPF}")
print("first:", rows[:3])
print("typo check:", [(w, w in dict(rows)) for w in ("модеш", "бузе", "ьув", "можеш", "буде", "був")])
