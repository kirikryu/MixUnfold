# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
版本历史自 v1.6.2 起（更早无记录）；每个版本的完整改动见
[GitHub Releases](https://github.com/kirikryu/MixUnfold/releases)。

## [1.7.0] - 2026-10-31

- 新增：内屏展开态锁屏允许横竖旋转（新增作用域 **系统界面**，总开关
  「锁屏允许横屏」默认开）
- 新增：配置项 `cetus_home_lockscreen_rot`
- UI：配置页文字颜色改为主题自适应

## [1.6.5] - 2026-10-30

- 修复：配置页浅色背景文字不可读（文字颜色主题自适应 + WCAG AA 对比度保底）

## [1.6.4] - 2026-10-30

- 修复：折叠态实时判定与方向请求重发，修复外屏误旋转 / 内屏旋转失效
- 构建：签名与构建配置通用化（`signing.properties`、标准 debug 签名、
  CI Secrets 注入）；输出目录缺失时自动创建，修复干净克隆构建失败
- CI：校验值迁出 Release 正文，改随附件 `SHA256SUMS.txt` 分发；
  新增 gitleaks 全历史密钥扫描与 pre-commit 防线

## [1.6.2] - 2026-10-30（初始发布，Pre-release）

- 旋转解锁：展开内屏后桌面跟随系统横竖旋转
- 统一网格 + 外侧边距独立可调
- 两页反向对齐：页缝两侧列距一致
- 横屏三页模式（可选）
- 控制面板：滑杆调参 + 一键重启桌面

[1.7.0]: https://github.com/kirikryu/MixUnfold/compare/v1.6.5...v1.7.0
[1.6.5]: https://github.com/kirikryu/MixUnfold/compare/v1.6.4...v1.6.5
[1.6.4]: https://github.com/kirikryu/MixUnfold/compare/v1.6.2...v1.6.4
[1.6.2]: https://github.com/kirikryu/MixUnfold/releases/tag/v1.6.2
