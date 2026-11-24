# 署名与许可

项目源码 © 2026 ChencLi819，采用 LGPL-3.0-or-later。随附 LGPL 许可及其引用的 [GNU GPL v3](licenses/GPL-3.0.txt)。

## libgphoto2

Canon EOS 属性、事件解析和 PTP/IP 会话实现参考并改写自 [libgphoto2 的 PTP2 驱动](https://github.com/gphoto/libgphoto2/tree/master/camlibs/ptp2)。改写使用 Kotlin，并与 Android 的网络和事件处理集成。此处记录 2026 年 11 月 24 日整理的 1.7.8 版。上游相关文件采用 LGPL 第 2 版或后续版本。

- `ptp-pack.c`、`ptp.c` 和 `ptp.h`
  - Copyright (C) 2001, 2001–2004 Mariusz Woloszyn <emsi@ipartners.pl>
  - Copyright (C) 2003–2019, 2003–2020, 2003–2024 Marcus Meissner <marcus@jet.franken.de>
  - Copyright (C) 2006–2008 Linus Walleij <triad@df.lth.se>
  - Copyright (C) 2007 Tero Saarni <tero.saarni@gmail.com>
  - Copyright (C) 2009–2024 Axel Waggershauser <awagger@web.de>
- `config.c` · Copyright (C) 2003–2024 Marcus Meissner <marcus@jet.franken.de>
- `ptpip.c` · Copyright (C) 2006 Marcus Meissner <marcus@jet.franken.de>
- `library.c`
  - Copyright (C) 2001–2005 Mariusz Woloszyn <emsi@ipartners.pl>
  - Copyright (C) 2003–2026 Marcus Meissner <marcus@jet.franken.de>
  - Copyright (C) 2005 Hubert Figuiere <hfiguiere@teaser.fr>
  - Copyright (C) 2009–2024 Axel Waggershauser <awagger@web.de>

## camera_control_dart

取景包中的对焦坐标和状态参考了 [camera_control_dart](https://github.com/JulianSchroden/camera_control_dart)。[MIT 许可与署名](licenses/camera_control_dart-MIT.txt)。

## Gradle Wrapper

Gradle Wrapper 由 Gradle 生成，采用 [Apache License 2.0](licenses/Apache-2.0.txt)。
