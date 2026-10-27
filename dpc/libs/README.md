# PDF processing dependency

`pdfbox-android-2.0.27.0.jar` is the unchanged `classes.jar` extracted from:

https://repo.maven.apache.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.aar

- Upstream: https://github.com/TomRoush/PdfBox-Android/tree/v2.0.27.0
- AAR SHA-256: `30277f879cfd571db2a137582c95516a0d4ea6778e945519bc58ca93d57d88c7`
- JAR SHA-256: `35f592ed840ec0903546b086e6181589796edcf0a142e2a5d5f3a3cf1e74739c`
- Its 112 asset files are copied unchanged under `dpc/assets/com/`.
- Apache-2.0 and bundled third-party licenses/NOTICE are included under `dpc/assets/licenses/pdfbox-*`.
- Only unencrypted PDF page merging/extraction is supported. BouncyCastle encryption/signature dependencies are not bundled; encrypted files are rejected, not decrypted or modified.
- No Gradle migration: the existing manual build compiles against the JAR and passes it to d8. Regenerate/review these checksums when upgrading.

On-device regression (`dpc/test/run-toolkit-device.ps1`) verifies generated text PDFs, page order, extraction and Android bitmap conversion without touching user documents.
