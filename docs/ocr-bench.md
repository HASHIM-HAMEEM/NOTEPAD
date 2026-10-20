# OCR bench (2026-10-20)

Synthetic pages rendered with Noto Naskh / Amiri (Arabic), Noto Nastaliq Urdu, Noto Serif (English), plus a simulated scan
(1.5° skew, grey paper, blur, noise, JPEG q45). Host Tesseract 4.1.1 (the app ships tesseract4android 4.7 / Tesseract 5),
token accuracy after NFKC + tashkeel stripping. Scripts: ~/work/ocr/{gen.py,gen2.py,bench.py,bench2.py}.

| page | lang | fast raw | best raw | best deskew | best deskew+bin |
|---|---|---|---|---|---|
| Arabic 200 dpi | ara+eng | 96.3 | 96.3 | 96.3 | 95.1 |
| Arabic 300 dpi | ara+eng | 95.1 | 96.3 | 96.3 | 96.3 (adaptive 97.6) |
| Mixed Ar/En 300 dpi | ara+eng | 98.5 | 95.4 | 95.4 | 96.9 |
| English | eng | 100 | 100 | 100 | 100 |
| Urdu Nastaliq 300 dpi | urd | 32.6 | 37.2 | 37.2 | 34.9 |
| Arabic scan | ara+eng | 96.3 | 96.3 | 96.3 | 96.3 |
| Mixed scan | ara+eng | 95.4 | 95.4 | 96.9 | 96.9 |
| Urdu scan | urd | 16.3 | 18.6 | 32.6 | 46.5 |

Decisions taken in the app:
- Deskew always (projection profile, ±3°), global Otsu-midpoint binarisation only for scan-like pages
  (`OcrPreprocess`); adaptive binarisation hurt clean renders and Nastaliq.
- Render every script at 300 dpi (Arabic was 200).
- Optional urd/fas models now come from tessdata_best.
- Urdu books are usually filed as Arabic notebooks; when the ara pass is weak (< 65 mean confidence) and the Urdu model
  is installed, the page is re-run with urd+eng and the better pass kept.
- Tesseract's Nastaliq recognition stays weak (≈ 35–46 %). A cloud engine (Google Cloud Vision / Azure Document
  Intelligence) is the realistic path to good Urdu OCR.
