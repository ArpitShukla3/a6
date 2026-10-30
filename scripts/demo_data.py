#!/usr/bin/env python3
"""Demo data generator.

  demo_data.py batch START COUNT            -> JSON batch creating employees D<START>..D<START+COUNT-1>
  demo_data.py snapshot TOTAL ROWS [--cut]  -> CSV export of D0..D<TOTAL-1>, only the first ROWS rows;
                                               --cut leaves out the trailer (file cut short)
"""
import datetime
import json
import sys
import uuid


def employee(i):
    return {"name": f"Demo {i}", "email": f"d{i}@acme.test", "phone": "+919876500000",
            "officeCode": "BLR-01", "shiftCode": "S1"}


def batch(start, count):
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    events = [{"type": "CREATE", "externalId": f"D{i}", "sourceVersion": 1,
               "occurredAt": now, "hr": employee(i)} for i in range(start, start + count)]
    print(json.dumps({"batchId": str(uuid.uuid4()), "events": events}))


def snapshot(total, rows, cut):
    as_of = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    # An honest export announces the rows it contains; a cut-off one announced more than it delivered.
    print(f"#ROSTER_EXPORT,as_of={as_of},expected_rows={total if cut else rows}")
    print("external_id,source_version,name,email,phone,manager_external_id,office_code,shift_code")
    for i in range(rows):
        e = employee(i)
        print(f"D{i},1,{e['name']},{e['email']},{e['phone']},,{e['officeCode']},{e['shiftCode']}")
    if not cut:
        print(f"#END,rows={rows}")


if __name__ == "__main__":
    if sys.argv[1] == "batch":
        batch(int(sys.argv[2]), int(sys.argv[3]))
    elif sys.argv[1] == "snapshot":
        snapshot(int(sys.argv[2]), int(sys.argv[3]), "--cut" in sys.argv)
