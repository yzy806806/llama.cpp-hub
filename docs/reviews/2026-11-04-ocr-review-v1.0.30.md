# OCR 审查报告 — v1.0.30（托盘重启修复）

- 日期：2026-11-04
- 范围：`31cf9df..HEAD`（工作区）
- 可审文件：`src/main/java/org/mark/llamacpp/server/LlamaServer.java` +95/−18
- 非代码改动：`FORK.md` +35/−2（OCR 按 unsupported_ext 排除，人工核对）
- 审查方式：`ocr delegate preview` + `ocr delegate rule`，diff 人工逐行复核

## 改动内容

`restartApplication()` 不再无条件用 `getInputArguments()` 重建 java 命令行：

1. 优先 `ProcessHandle.current().info().command()` 取自身可执行文件；Windows 上为
   `llama.cpp-hub.exe`，直接重启启动器（启动器重读 `launcher.conf`，参数从零生成）。
2. 取不到启动器时回退：`LinkedHashSet` 去重 + 剔除 `-Djava.class.path=` /
   `-Dsun.java.command=` / `-Dsun.java.launcher=`；重建后 > 30,000 字符抛
   `IOException("重建的命令行过长…请手动关闭并重新启动程序")`。
3. 新增私有方法 `findNativeLauncher(File)`、`buildJavaRestartCommand()`。

## 发现

| # | 级别 | 内容 | 处置 |
|---|------|------|------|
| 1 | 提示 | 回退路径长度超限时只记录 `重启失败` 并返回，此时 Web 服务已停、进程仍在，仍会留下「进程在、服务没了」状态 | 接受：该分支仅在无原生启动器时可达；错误信息已明确要求手动重启，日志可见 |
| 2 | 提示 | `java.lang.management.RuntimeMXBean` 导入在改动后仅剩 `buildJavaRestartCommand()` 内的 `ManagementFactory.getRuntimeMXBean()` 使用，import 仍在用，无未使用告警 | 接受，无需改动 |
| 3 | 提示 | `findNativeLauncher` 以「自身可执行文件名不以 java/java.exe/javaw.exe 结尾」判定启动器，属启发式；若将来改用其它包装器可能误判 | 接受：判定失败会落到按文件名查找 `llama.cpp-hub.exe` 的兜底 |

无阻断性问题。

## 验证

- 编译：`javac --release 21` 通过（仅既有 deprecated API 提示）
- 部署校验：目标机 `classes/.../LlamaServer.class` sha256 与本地构建一致
  （`29e3ba5c…2c659`），旧 class 备份于 `classes-backup-20261104/`
- 运行时验证：需手动重启 hub 一次使新字节码生效；之后托盘「重启」预期日志
  `重启进程已启动: …\llama.cpp-hub.exe`
