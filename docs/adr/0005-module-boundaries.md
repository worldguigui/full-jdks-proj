# ADR-0005 模块依赖方向与 SPI 注入

## 背景

`Location`、`Link`、`GameEvent` 的 sealed 子类型必须与 sealed 根同模块。模拟管线又需要调用生产、市场、势力与殖民地结算，如果这些实现直接放进 `fc-sim`，会让核心模拟依赖全部领域模块，形成循环与职责混杂。

## 决策

- 依赖方向固定为 `fc-world ← fc-config ← fc-sim / 领域模块 ← fc-cli / fc-runtime`。
- `fc-sim` 定义 `ProductionSystem`、`MarketSystem`、`FactionSystem`、`ColonySystem` 四个 SPI；领域模块实现，`fc-runtime` 负责注入。
- 领域模块之间不互相依赖；跨域协作通过共享 `WorldState` 与 `GameEvent`。
- 领域模块只提供行为与服务，不新增 sealed 子类型。
- 打包只由 `fc-runtime` 完成；构建工具（Maven）不属于运行时依赖。

## 结果

- `fc-sim` 不依赖任何领域模块，可以独立测试管线与确定性。
- 领域模块可单独替换实现，但必须遵守 SPI 的状态写入边界。
