#!/usr/bin/env python3
"""Removes the MethodParameters attribute from .class files.

Why: javac 21 writes MethodParameters entries without names for the
constructors of anonymous classes, and D8 8.2 (build-tools 34) crashes on
them with a NullPointerException. The attribute is only used for reflection,
so dropping it changes nothing at runtime.

Usage: strip_method_params.py <classes-dir>
"""
import struct
import sys
from pathlib import Path

# constant pool tag -> payload size in bytes (Utf8 is variable)
SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
         15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


def strip(data: bytes) -> tuple[bytes, int]:
    pos = 8
    (cp_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = {}
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (n,) = struct.unpack_from(">H", data, pos)
            utf8[i] = data[pos + 2:pos + 2 + n].decode("utf-8", "replace")
            pos += 2 + n
        else:
            pos += SIZES[tag]
            if tag in (5, 6):
                i += 1  # long/double take two slots
        i += 1

    out = bytearray(data[:pos])
    pos += 6  # access, this, super
    (n_if,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * n_if
    out += data[len(out):pos]
    removed = 0

    def copy_attrs(drop: bool) -> None:
        nonlocal pos, removed
        (count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        kept = []
        for _ in range(count):
            name_idx, length = struct.unpack_from(">HI", data, pos)
            raw = data[pos:pos + 6 + length]
            pos += 6 + length
            if drop and utf8.get(name_idx) == "MethodParameters":
                removed += 1
                continue
            kept.append(raw)
        out.extend(struct.pack(">H", len(kept)))
        for raw in kept:
            out.extend(raw)

    for drop in (False, True):  # fields, then methods
        (count,) = struct.unpack_from(">H", data, pos)
        out.extend(data[pos:pos + 2])
        pos += 2
        for _ in range(count):
            out.extend(data[pos:pos + 6])  # access, name, descriptor
            pos += 6
            copy_attrs(drop)
    copy_attrs(False)  # class attributes
    if pos != len(data):
        raise ValueError("unexpected trailing bytes")
    return bytes(out), removed


def main() -> None:
    total = 0
    for f in Path(sys.argv[1]).rglob("*.class"):
        data = f.read_bytes()
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError(f"{f} is not a class file")
        new, removed = strip(data)
        if removed:
            f.write_bytes(new)
            total += removed
    print(f"removed {total} MethodParameters attribute(s)")


if __name__ == "__main__":
    main()
