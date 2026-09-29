# 纸阅 1.6.0

发布日期：2026-09-29。包名 `cn.paperpdf.reader`，versionCode 7，应用名称“纸阅”。

[安装包](../release-assets/PaperPDF-1.6.0.apk) · SHA-256：`83CFBE2B0C43BB26F63165773BB70EEE64C90BDE73D4AB2C085416AD43991A23`

新增 DOCX 和旧版 DOC 阅读。Word 复用单页/连续阅读、搜索、缩放、批注和草稿；可修改段落及表格单元格文字，并保存可编辑的 DOCX。旧版 DOC 修改后另存 DOCX；旋转、删除或重排 Word 页面时导出 PDF 以保留结果。复杂 Word 版式采用兼容预览，详见 [README](../README.md)。

`build.ps1 -Test`：33 项 Android 11 模拟器集成测试通过，0 失败。1.5.0 → 1.6.0 覆盖安装、启动和 release 签名校验通过。尚无用户实体手机或 Microsoft Word/WPS 实机验收反馈。

本地详细证据位于 `delivery/PaperPDF-1.6.0-验证记录.md` 和 `docs/verification-1.6.0/`；这些生成的测试产物不提交源码仓库。
