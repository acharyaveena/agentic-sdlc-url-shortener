# Decision lineage

Hashes are the first 10 characters of SHA-256.

| stage | revision | input | output | model | commit | built from |
|---|---|---|---|---|---|---|
| requirements | 1 | 264bd30c2f | 98d013dd77 | claude-opus-5.5 | 86aace33fc | - |
| impact-analysis | 1 | 31bede8f94 | 3a4d57826b | claude-opus-5.5 | ae592abf93 | requirements=98d013dd77 |
| design | 1 | a6ef33ee06 | 085795a56f | claude-opus-5.5 | 8f4f446949 | requirements=98d013dd77, impact-analysis=3a4d57826b |
| impact-analysis | 2 | 81a12e896b | 42404c11cd | claude-opus-5.5 | a51e5ecb55 | requirements=98d013dd77 |
| design | 2 | 0fe1e489f1 | a7af940a74 | claude-opus-5.5 | 8bcb411b61 | requirements=98d013dd77, impact-analysis=42404c11cd |
| design | 3 | 2c4674a99f | ee6a4f59cb | claude-opus-5.5 | 4508a127ad | requirements=98d013dd77, impact-analysis=42404c11cd |
| code | 1 | 30e76f5aa8 | c3e63fb67f | claude-sonnet-5.5 | 09ac4f6297 | requirements=98d013dd77, impact-analysis=42404c11cd, design=ee6a4f59cb |
| tests | 1 | 30e76f5aa8 | 12fe20668d | claude-sonnet-5.5 | 81a702954f | requirements=98d013dd77, impact-analysis=42404c11cd, design=ee6a4f59cb |
