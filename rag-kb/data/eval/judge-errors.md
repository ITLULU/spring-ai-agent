# Judge 失误清单

人工复核评测失败 case 时记录的裁判误判。每攒约 10 条，
把典型误判作为 few-shot 反例追加进 Judge prompt，并跑 smoke 验证裁判行为变化（方案 §4.2）。

- **2026-10-16** | run=20261016-154222-probe6-9820 case=att-003 | Judge判=INCORRECT → 人判=CORRECT | 备注: probe 验证：答案语义等价
