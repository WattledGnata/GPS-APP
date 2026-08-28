# Track Thumbnails V2

这些文件由 `/Users/wattledgnata/traeprojects/laptime/miniprogram/track_images` 的赛道图批处理生成，风格对齐 Track Tech V2：暗底、细网格、cyan 赛道线、轻微发光、少量紫色 HUD 点缀。

## Output

- `transparent/*.png`
  - 透明底赛道线资产。
  - 尺寸：`960 x 540`。
  - 推荐用于 App 里的赛道卡片、Records/Laps 缩略图、可叠加到 Compose 面板背景上。

- `preview/*.png`
  - 暗底预览图。
  - 尺寸：`960 x 540`。
  - 只用于设计评审和视觉对齐，不建议直接作为 App production 背景。

- `contact-sheet.png`
  - 全部赛道预览总览。

- `highres/chengdu-tianfu-transparent-3840.png`
  - 由 51GT3 高清 PDF 渲染生成的透明底成都天府赛道线资产。
  - 尺寸：`3840 x 2160`。
  - 比 `transparent/*.png` 里的旧 JPG 批处理版本更适合大卡片、详情页、横屏展示。
  - 正式 UI 优先使用这类透明底资产，不要把暗底预览图当 production 素材。

- `highres/chengdu-tianfu-transparent-1440.png`
  - 由高清源压缩生成的透明底卡片尺寸资产。
  - 尺寸：`1440 x 810`。
  - 推荐用于 Laps/Records 的大卡片和普通手机页面。

- `highres/chengdu-tianfu-transparent-720.png`
  - 由高清源压缩生成的透明底小缩略图资产。
  - 尺寸：`720 x 405`。
  - 推荐用于赛道选择列表、附近赛道列表、小型 Records 卡片。

- `../../feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png`
  - App 运行时小缩略图资产。
  - 由 `highres/chengdu-tianfu-transparent-720.png` 复制而来。
  - 尺寸：`720 x 405`，透明底，有 alpha 通道。
  - Asset path: `track_thumbnails/chengdu_tianfu.png`。

- `highres/chengdu-tianfu-preview-3840.png`
  - 同一张高清资产的暗底预览图。
  - 尺寸：`3840 x 2160`。
  - 只用于肉眼检查透明资产效果，不作为 App 正式素材。

## Source PDFs

- `../track-source-pdfs/chengdu-tianfu-51gt3.pdf`
  - Source: `https://img2.51gt3.com/rac/track/202312/371b68b960a3437cb30b8c96fd82e269.pdf`
  - PDF title: `51GT3.COM HD Track Map-Chengdu Tianfu International Circuit`
  - Adobe Illustrator PDF, suitable for high-resolution rendering.

## Regenerate

```bash
python3 docs/design/track-thumbnails-v2/generate_track_thumbnails.py
```

```bash
pdftoppm -png -r 300 -singlefile docs/design/track-source-pdfs/chengdu-tianfu-51gt3.pdf docs/design/track-source-pdfs/chengdu-tianfu-51gt3-300dpi
python3 docs/design/track-thumbnails-v2/generate_highres_pdf_track.py
```

## Notes

- 源图本身有文字、编号、刹车点标注；脚本通过连通块过滤尽量只保留赛道主体。
- 少量和赛道线粘连的原图标注可能残留，后续可以按单张图做人工修补。
- 这些是可用的视觉素材，但不是最终品牌图标体系；如果进入 production，建议先在真实 App 截图里确认尺寸、发光强度和背景对比。
- 旧 JPG 源图只有约 `500-600px` 宽，只适合小缩略图；PDF 源更适合沉淀为可放大的赛道资产。
- 起终点线不是噪点。高清派生脚本会把它显式画成棋盘起终点标记，保证缩略图里也能读出赛道方向和起终点位置。
