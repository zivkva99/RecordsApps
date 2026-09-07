"""
Builds a ready-to-push replacement for the app's on-device database +
cover images, from examples/recognition_results.json.

Does NOT touch the phone. Produces:
  install_staging/records_database   -- SQLite db, ready for `adb push`
  install_staging/files/cover_*.jpg  -- cover images, ready for `adb push`
  install_staging/manifest.json      -- filename -> assigned cover_<uuid>.jpg, for review

Starts from a real, previously-synced records_database.db as a schema
template (preserves Room's room_master_table identity_hash exactly --
building a fresh SQLite file from scratch risks Room rejecting it as a
schema mismatch on next app launch), then replaces all row content.

Usage: python tools/build_install_staging.py
Then: .\\install_collection.ps1 (from the repo root) to push it to a device.
"""
import json
import os
import shutil
import sqlite3
import sys
import uuid

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMPLES_DIR = os.path.join(REPO, "examples")
TEMPLATE_DB = os.path.join(REPO, "records_database.db")
STAGING = os.path.join(REPO, "install_staging")
FILES_DIR = os.path.join(STAGING, "files")

if os.path.isdir(FILES_DIR):
    shutil.rmtree(FILES_DIR)  # drop stale cover_*.jpg from a previous run
os.makedirs(FILES_DIR, exist_ok=True)

data = json.load(open(os.path.join(EXAMPLES_DIR, "recognition_results.json"), encoding="utf-8"))

db_path = os.path.join(STAGING, "records_database")
shutil.copyfile(TEMPLATE_DB, db_path)

conn = sqlite3.connect(db_path)
cur = conn.cursor()
cur.execute("DELETE FROM copies")
cur.execute("DELETE FROM albums")
cur.execute("DELETE FROM sqlite_sequence WHERE name IN ('albums', 'copies')")

manifest = []
skipped = []

for d in data:
    filename = d["filename"]
    base = os.path.splitext(filename)[0]
    choice = d.get("coverChoice")

    if choice and choice.get("source") == "itunes":
        src = os.path.join(EXAMPLES_DIR, "covers", base + ".jpg")
    else:
        src = os.path.join(EXAMPLES_DIR, filename)

    if not os.path.exists(src):
        skipped.append((filename, "source image missing: " + src))
        continue

    device_filename = f"cover_{uuid.uuid4()}.jpg"
    shutil.copyfile(src, os.path.join(FILES_DIR, device_filename))
    device_path = f"/data/data/com.recordsapp/files/{device_filename}"

    try:
        year = int(d["year"])
    except ValueError:
        year = 0
    try:
        num_records = int(d["numRecords"])
    except ValueError:
        num_records = 1

    cur.execute(
        "INSERT INTO albums (artistName, albumName, numRecords, year, coverImagePath, comment) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (d["artistName"], d["albumName"], num_records, year, device_path, ""),
    )
    album_id = cur.lastrowid
    cur.execute(
        "INSERT INTO copies (albumId, gradeSide1, gradeSide2, country, listened) "
        "VALUES (?, ?, ?, ?, ?)",
        (album_id, "", "", "Unknown", 0),
    )
    manifest.append({
        "filename": filename, "artistName": d["artistName"], "albumName": d["albumName"],
        "coverSource": "itunes" if (choice and choice.get("source") == "itunes") else "photo",
        "devicePath": device_path,
    })

conn.commit()
cur.execute("SELECT COUNT(*) FROM albums")
album_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM copies")
copy_count = cur.fetchone()[0]
conn.close()

with open(os.path.join(STAGING, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)

print(f"Staged {album_count} albums, {copy_count} copies")
print(f"Cover images staged: {len(os.listdir(FILES_DIR))}")
if skipped:
    print(f"Skipped {len(skipped)}:")
    for fn, reason in skipped:
        print(" ", fn, reason)
print(f"\nDatabase: {db_path}")
print(f"Files:    {FILES_DIR}")
