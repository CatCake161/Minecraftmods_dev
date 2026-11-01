# Cinema P0 Spike（独立基准工程）

这是 P0 阶段的独立 JavaCV/OpenAL 技术验证工程，**不参与主模组构建**，也不被主模组依赖。

完整的测试数据、结论和渲染坑见：

- [P0 技术验证报告](../docs/design/P0-SPIKE-REPORT.md)
- [P0.5 投影/扬声器设计](../docs/design/P05-DESIGN.md)

运行方式（在仓库根目录）：

```powershell
$env:JAVA_HOME='<JDK-25+>'
.\gradlew -p spike run --args="caps"
```

主模组现在使用外部 FFmpeg，不需要 JavaCV；本目录保留用于历史对比和底层测量。
