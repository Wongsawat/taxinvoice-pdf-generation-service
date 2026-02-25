# Thai Fonts for Tax Invoice PDF Generation

This directory contains Thai fonts for PDF/A-3 generation.

## Installed Fonts

### Noto Sans Thai Looped (active)
Google's open source font with excellent Thai support. Uses traditional looped letterforms, suitable for formal/business documents.

Download from: https://fonts.google.com/noto/specimen/Noto+Sans+Thai+Looped

Installed files:
- `NotoSansThaiLooped-Regular.ttf` (Regular)
- `NotoSansThaiLooped-Bold.ttf` (Bold)

Registered in `fop.xconf` under the font-triplet name `NotoSansThai`.

## Alternative Fonts

### TH Sarabun New
Official Thai government font, free to use. Add these files to this directory and register them in `fop.xconf` to use instead.

Download from: https://www.f0nt.com/release/th-sarabun-new/

Required files:
- `THSarabunNew.ttf` (Regular)
- `THSarabunNew-Bold.ttf` (Bold)
- `THSarabunNew-Italic.ttf` (Italic)
- `THSarabunNew-BoldItalic.ttf` (Bold Italic)

If installed, `taxinvoice.xsl` will prefer TH Sarabun New first (it appears first in the `font-family` fallback chain).

## Docker Usage

When using Docker, fonts can be mounted as a volume:

```bash
docker run -v /path/to/fonts:/app/fonts taxinvoice-pdf-generation-service
```

Or install system fonts in the Dockerfile:

```dockerfile
RUN apk add --no-cache font-noto font-noto-thai
```

## License

- TH Sarabun New: Public Domain (Thai Government)
- Noto Sans Thai Looped: SIL Open Font License 1.1
