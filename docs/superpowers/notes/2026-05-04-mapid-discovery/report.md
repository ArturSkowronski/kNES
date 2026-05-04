# MapId Discovery RAM-Diff (2026-05-04)

Method: load post-boot fixture → walk to (145,152) → tap UP to enter interior.

## Final state
- world=(146,152)
- local=(4,25)
- locationType=0x0
- $0048 (currentMapId profile)=8

## Total changed bytes: 68
## Candidates (post-noise-filter): 20

| Address | Before | After | Likely meaning |
|---|---|---|---|
| $002B | 0x92 | 0x04 | (investigate) |
| $002C | 0x9E | 0x19 | (investigate) |
| $002F | 0x00 | 0x0C | (investigate) |
| $0030 | 0x00 | 0x0C | (investigate) |
| $0031 | 0x02 | 0x14 | (investigate) |
| $0033 | 0x00 | 0x08 | (investigate) |
| $0041 | 0x5F | 0x2C | (investigate) |
| $0048 | 0x00 | 0x08 | (investigate) |
| $0049 | 0x00 | 0x01 | (investigate) |
| $004A | 0x00 | 0x70 | (investigate) |
| $0053 | 0x76 | 0x02 | (investigate) |
| $0068 | 0x00 | 0x0B | (investigate) |
| $0069 | 0x06 | 0x20 | (investigate) |
| $007C | 0x00 | 0x48 | (investigate) |
| $00F0 | 0x56 | 0x78 | (investigate) |
| $00F1 | 0x00 | 0x01 | (investigate) |
| $00F4 | 0x1B | 0xAB | (investigate) |
| $00F8 | 0x0A | 0x08 | (investigate) |
| $00FD | 0x09 | 0x08 | (investigate) |
| $00FF | 0x09 | 0x08 | (investigate) |

## Indoor coord probe
- $29 Δ=2, $2A Δ=0
- $68 Δ=2, $69 Δ=0
