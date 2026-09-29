"""Detect the amber-outlined button wells inside a panel texture.

The panel art is a clean background: a dark plate with amber rectangles marking
where buttons go. Rather than eyeballing coordinates, scan the PNG and report
the bounding box of every distinct amber rectangle, so the Screen classes can
place their widgets exactly on the art.
"""

import sys
import struct
import zlib

def load_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos = 8
    width = height = None
    idat = b''
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b'IHDR':
            width, height, depth, color, _, _, interlace = struct.unpack('>IIBBBBB', chunk)
            assert depth == 8 and color == 6 and interlace == 0, \
                f'unsupported png: depth={depth} color={color} interlace={interlace}'
        elif ctype == b'IDAT':
            idat += chunk
        elif ctype == b'IEND':
            break
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = width * 4
    pix = bytearray(width * height * 4)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 0:
                v = line[x]
            elif f == 1:
                v = (line[x] + a) & 0xFF
            elif f == 2:
                v = (line[x] + b) & 0xFF
            elif f == 3:
                v = (line[x] + ((a + b) >> 1)) & 0xFF
            else:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                v = (line[x] + pr) & 0xFF
            line[x] = v
        pix[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, pix


def is_amber(r, g, b):
    return r > 120 and r > b + 40 and g > b


def main(path):
    width, height, pix = load_png(path)
    stride = width * 4
    amber = set()
    for y in range(height):
        for x in range(width):
            i = y * stride + x * 4
            r, g, b = pix[i], pix[i + 1], pix[i + 2]
            if is_amber(r, g, b):
                amber.add((x, y))
    print(f'{path}: {width}x{height}, amber px = {len(amber)}')

    # group amber pixels into rows
    rows = sorted({y for _, y in amber})
    bands = []
    for y in rows:
        if bands and y - bands[-1][-1] <= 2:
            bands[-1].append(y)
        else:
            bands.append([y])
    for band in bands:
        ys = set()
        for y in band:
            for x in range(width):
                if (x, y) in amber:
                    ys.add(x)
        xs = sorted(ys)
        # split the row into contiguous runs (separate wells on the same line)
        runs = []
        for x in xs:
            if runs and x - runs[-1][-1] <= 8:
                runs[-1].append(x)
            else:
                runs.append([x])
        for run in runs:
            print(f'  y={band[0]:>3}-{band[-1]:<3}  x={run[0]:>3}-{run[-1]:<3}'
                  f'   -> well ({run[0]}, {band[0]})  size {run[-1]-run[0]+1}x{band[-1]-band[0]+1}')


if __name__ == '__main__':
    for p in sys.argv[1:]:
        main(p)
