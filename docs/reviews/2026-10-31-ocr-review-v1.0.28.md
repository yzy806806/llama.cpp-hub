# OCR 审查 — v1.0.28 参数表同步（2026-10-31）

## 范围

```
origin/master(e185946)..0636bb6    3 文件 / +237 −17
  src/main/resources/server-params.json         +181 −13
  src/main/resources/web/i18n/zh-CN.json        +28 −2
  src/main/resources/web/i18n/en-US.json        +28 −2
```

工具：`ocr delegate preview` → `ocr delegate rule`；每条 diff 自己读，参数逐项对照
llama-server **b11960**（v0.8.0）的实际 `--help`。

## 结论

无阻塞项。两条候选问题经核实均为**误报/设计如此**，未改代码。

| # | 候选 | 判定 | 依据 |
|---|------|------|------|
| 1 | `--spec-type_ngram-mod`、`--spec-type_ngram-map-k4v` 不在二进制 `--help` 里 | **设计如此，非 bug** | `LlamaServerManager.splitSpecType` 把 `--spec-type_xxx` 改写成 `--spec-type xxx` 并与下拉值合并。反射测试 4 例全过（含 `draft-mtp` + `ngram-mod` 合并、`none` 过滤） |
| 2 | 新增项 `-nkvo` / `-ot` 的缩写“找不到” | **误报** | help 里是多别名行：L68 `-kvo, --kv-offload, -nkvo, --no-kv-offload`；L120 `-ot, --override-tensor ...`。检查脚本按单别名前缀匹配才漏判 |

## 通过项

- 95 个参数的 `fullName` 全部能在 b11960 `--help` 找到（除上述两个按设计改写的合成项）。
- 13 个新增项的 type / defaultValue / abbreviation 与二进制一致。
- `--spec-type` 的 9 个取值全部在 help 的合法枚举内。
- 无重复 `fullName`；同 group 内无 `sort` 冲突。
- zh-CN / en-US 各 +26 键，95 个参数的 name/desc 全覆盖，无缺失。
- `mlock` 残留只剩 `--load-mode` 的合法取值与迁移说明，无死条目。
- 运行态：`/api/models/param/server/list` 返回 95 项，spec-type 9 个取值，`--moe-cache-mib` 在内。

## 构建与测试

```
javac-linux.sh                  exit 0
SecurityFixTest                 14/14
splitSpecType 反射测试          4/4
```

## 说明

新参数全部 `defaultEnabled: false`，只有用户在表单里启用才会出现在命令行，默认行为不变。
`--lora` 与 `--lora-scaled` 同时启用会输出两个 flag——llama.cpp 接受，但属用户选择，未做互斥。
