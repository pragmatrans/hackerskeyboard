#!/usr/bin/env python3
"""Build the Ukrainian word/frequency asset for the keyboard.

Sources:
  wordfreq (Apache-2.0) - corpus-derived frequencies, top-N vocabulary
  uk_UA hunspell (MPL 1.1) - validity filter, drops junk / proper-name noise

Output: assets/uk_words.txt.gz, lines "word<TAB>freq" sorted by freq desc.
Freq is occurrences per billion words (int), so it fits in int32.
"""
import gzip
import re
import sys

from spylls.hunspell import Dictionary
from wordfreq import top_n_list, zipf_frequency

TOP_N = int(sys.argv[2]) if len(sys.argv) > 2 else 200000
MIN_ZIPF = 2.0
CYR = re.compile(r"^[а-яіїєґ]+(?:['’][а-яіїєґ]+)?$")

dic = Dictionary.from_files("uk_UA")

rows = []
for word in top_n_list("uk", TOP_N * 3):
    w = word.lower()
    if len(w) < 2 or not CYR.match(w):
        continue
    z = zipf_frequency(w, "uk")
    if z < MIN_ZIPF:
        continue
    if not dic.lookup(w):
        continue
    rows.append((w, int(10 ** z)))
    if len(rows) >= TOP_N:
        break

rows.sort(key=lambda r: (-r[1], r[0]))
with gzip.open(sys.argv[1], "wt", encoding="utf-8", compresslevel=9) as f:
    for word, freq in rows:
        f.write(f"{word}\t{freq}\n")

print(f"kept={len(rows)}")
print("first:", rows[:5])
print("last:", rows[-3:])
print("typo check:", [(w, dic.lookup(w)) for w in
      ("модеш", "бузе", "ьув", "можеш", "буде", "був")])
