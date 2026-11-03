#!/usr/bin/env python3
"""Converts the old site's database backup (mysqldump .sql.gz) into the member import CSV.

    python3 -I scripts/legacy-to-csv.py BACKUP.sql.gz OUT.csv [--keep-inactive-years N]

What is carried over is decided by the data-minimisation list agreed on 26 Oct (candidate: e-mail, date of birth,
municipality) plus what the points need (member number, balance, registration date, status).
Never carried over: passwords, street address, phone, gender,
occupation, staff notes, social-login ids.

Prints a report of how many records fall into each group, so VIS-À-VIS can decide what to keep.
Use the clean backup agreed in the internal report.
Write OUT.csv outside the git repository: it contains personal data.
"""
import csv, gzip, re, sys
from collections import Counter
from datetime import datetime, timedelta


def tuples(line):
    i = line.index(" VALUES ") + 8
    row, buf, inq, esc, depth, was_null = [], "", False, False, 0, True
    for ch in line[i:]:
        if inq:
            if esc:
                buf += {"n": "\n", "r": "\r", "t": "\t", "0": "\0"}.get(ch, ch); esc = False
            elif ch == "\\": esc = True
            elif ch == "'": inq = False
            else: buf += ch
            continue
        if ch == "'": inq = True; was_null = False
        elif ch == "(" and depth == 0: depth, row, buf, was_null = 1, [], "", True
        elif ch == "," and depth == 1: row.append(None if was_null and buf == "NULL" else buf); buf, was_null = "", True
        elif ch == ")" and depth == 1:
            row.append(None if was_null and buf == "NULL" else buf); depth = 0; yield row
        elif depth == 1: buf += ch


def value(v):
    """Binary columns are dumped as 0x... hex; the old site stored its text in Shift_JIS (cp932)."""
    if v is not None and v.startswith("0x") and all(c in "0123456789ABCDEFabcdef" for c in v[2:]):
        return bytes.fromhex(v[2:]).decode("cp932", errors="replace")
    return v


def load(path, table):
    out = []
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith(f"INSERT INTO `{table}` VALUES"):
                out.extend([value(x) for x in t] for t in tuples(line))
    return out


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    src, out = sys.argv[1], sys.argv[2]
    keep_years = None
    if "--keep-inactive-years" in sys.argv:
        keep_years = int(sys.argv[sys.argv.index("--keep-inactive-years") + 1])
    # monitor columns (old site): pk, mail, pw, birthday, sex, civil, child, occupation, job, postcode1, postcode2, pref,
    # address1, address2, entry_date, login_date, answer_date, modify_date, point, nondelivery, nondelivery_date, bikou,
    # is_open, reg, salt, twid, fbid
    monitors = load(src, "monitor")
    withdrawn = {int(r[2]) for r in load(src, "secession") if r[2]}
    email_re = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
    now = datetime(2026, 10, 12)
    report, seen = Counter(), {}
    rows = []
    for r in monitors:
        report["rows in the old table"] += 1
        pk, mail = int(r[0]), (r[1] or "").strip()
        if not mail:
            report["skipped: no e-mail address (deleted or empty record)"] += 1; continue
        if not email_re.match(mail) or len(mail) > 254:
            report["skipped: e-mail address not valid"] += 1; continue
        key = mail.lower()
        if key in seen:
            report["skipped: same e-mail as an earlier member number"] += 1; continue
        seen[key] = pk
        status = "退会" if pk in withdrawn else "有効"
        last = max([d for d in (r[15], r[16], r[14]) if d and not d.startswith("0000")] or ["1900-01-01 00:00:00"])
        last_dt = datetime.strptime(last[:19], "%Y-%m-%d %H:%M:%S")
        points = int(r[18]) if r[18] and r[18].lstrip("-").isdigit() else 0
        if status == "退会":
            report["withdrawn (kept as 退会, no sign-in)"] += 1
        elif last_dt >= now - timedelta(days=365):
            report["active: login, answer or registration within 1 year"] += 1
        elif last_dt >= now - timedelta(days=3 * 365):
            report["inactive 1-3 years"] += 1
        else:
            report["inactive more than 3 years"] += 1
        if points > 0:
            report["members holding points"] += 1
            report["points held in total"] += points
        if keep_years is not None and status == "有効" and last_dt < now - timedelta(days=keep_years * 365) and points == 0:
            report[f"left out: inactive > {keep_years} years and no points"] += 1; continue
        birth = r[3][:10] if r[3] and not r[3].startswith("0000") else ""
        entry = r[14][:19] if r[14] and not r[14].startswith("0000") else ""
        rows.append([pk, mail, "", birth, "", "", r[11] or "", r[12] or "", "", "", points, entry, status, ""])
        report["written to the CSV"] += 1
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["monitor_no", "email", "name", "birth", "gender", "postcode", "prefecture", "city", "street", "tel", "points", "registered_at", "status", "remark"])
        w.writerows(rows)
    width = max(len(k) for k in report)
    for k, v in report.items():
        print(f"{k:<{width}}  {v:>9,}")


if __name__ == "__main__":
    main()
