#!/usr/bin/env python3
"""Dummy member CSV for staging tests (meeting 2 Nov). No real person's data.

Usage: python3 scripts/make-dummy-csv.py OUT.csv [ROWS]
The file includes the awkward cases the old database has: gaps in numbering, blank (NULL) fields,
a duplicated number, a duplicated e-mail, bad dates and an invalid status, so the import report can be checked.
"""
import csv, random, sys

out = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 200
random.seed(20261102)
prefs = ["岡山県", "広島県", "香川県", "兵庫県", "東京都"]
cities = {"岡山県": "岡山市北区", "広島県": "広島市中区", "香川県": "高松市", "兵庫県": "姫路市", "東京都": "千代田区"}
family = ["山田", "佐藤", "鈴木", "高橋", "田中", "伊藤", "渡辺", "中村"]
given = ["花子", "太郎", "美咲", "健太", "陽菜", "翔", "結衣", "大輔"]
cols = ["monitor_no", "email", "name", "birth", "gender", "postcode", "prefecture", "city", "street", "tel", "points", "registered_at", "status", "remark"]
rows, no = [], 0
for i in range(n):
    no += random.choice([1, 1, 1, 2, 5])  # gaps, like deleted rows on the old site
    pref = random.choice(prefs)
    rows.append([no, f"dummy{no:05d}@example.com", random.choice(family) + " " + random.choice(given),
                 f"{random.randint(1950, 2004)}-{random.randint(1, 12):02d}-{random.randint(1, 28):02d}", random.choice(["男性", "女性"]),
                 f"7{random.randint(0, 99):02d}-{random.randint(0, 9999):04d}", pref, cities[pref], f"{random.randint(1, 9)}-{random.randint(1, 30)}-{random.randint(1, 20)}",
                 f"090-{random.randint(1000, 9999)}-{random.randint(1000, 9999)}", random.choice([0, 0, 110, 500, 1500, 3230, 8690]),
                 f"20{random.randint(11, 25)}-{random.randint(1, 12):02d}-{random.randint(1, 28):02d} 10:00:00", "有効", ""])
# awkward cases
rows[3][2:5] = ["", "", ""]                          # blanks (stay NULL)
rows[4][3] = "NULL"                                   # literal NULL text
rows[5][3] = "1988/13/40"                             # impossible date -> rejected
rows[6][12] = "保留"                                  # unknown status -> rejected
rows[7][1] = rows[8][1]                               # duplicated e-mail -> second one rejected
rows.append(list(rows[10]))                           # duplicated monitor number -> rejected
rows[11][13] = '=HYPERLINK("http://evil.example","x")'  # formula text: stored as text, neutralised on export
rows[12][12] = "退会"                                 # withdrawn member
with open(out, "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(cols)
    w.writerows(rows)
print(f"wrote {len(rows)} rows to {out}")
