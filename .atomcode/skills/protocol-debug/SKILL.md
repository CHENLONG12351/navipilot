---
name: protocol-debug
description: 解析 Navipilot 的 5 种通信协议（UDP 7706 / TCP 7709 / TCP 7711 / HTTP 7000 / ZMQ 7710）
agent: atomcode
user_invocable: true
---

# Protocol Debug — Navipilot 协议调试

## 背景

Navipilot 与 comma3 设备之间通过 5 种通信协议交互。此 skill 提供协议字段解析、调试和问题排查支持。

## 协议速查

### UDP 7706 (→ comma3)
- **用途**: 实时导航数据推送，~5Hz
- **负载**: 44 字段 JSON
- **字段分组**:
  | 分组 | 字段数 | 说明 |
  |------|--------|------|
  | 基础通信 | 3 | timestamp, version, heartbeat |
  | GPS | 5 | lat, lng, speed, heading, altitude |
  | 目的地 | 3 | dest_lat, dest_lng, dest_name |
  | 限速 | 1+道路类别 | speed_limit, road_type |
  | SDI 电子眼 | 7 | 电子眼类型、距离、限速等 |
  | SDI Plus | 6 | 扩展电子眼信息 |
  | TBT 转弯 | 9 | 下一个转弯方向、距离、道路名 |
  | 剩余路程 | 3 | remaining_distance, remaining_time, arrival_status |
  | 导航 GPS | 4 | next_point_lat, next_point_lng, next_point_dist, next_point_bearing |
  | 命令通道 | 2 | command, command_param |

### TCP 7709 (→ comma3)
- **用途**: 路线规划完成后的路线点坐标批量发送
- **格式**: JSON 数组 `[{lat, lng}, ...]`

### TCP 7711 (← comma3)
- **用途**: 设备状态数据接收，5s 心跳
- **负载**: JSON（carState, modelV2, controlsState）
- **重连策略**: 指数退避 2s → 5s → 10s → 20s → 30s max

### HTTP 7000 (↔ comma3)
- **用途**: 参数读写 REST API
- **端点**: GET/PUT `/param/{name}`

### ZMQ 7710 (→ comma3)
- **用途**: 超车变道指令
- **格式**: `{"command": "overtake"}` 或 `{"command": "lane_change", "direction": "left|right"}`

## 使用方式

- `@protocol-debug "解析这条 UDP 7706 数据: {...}"` — 解析 UDP 负载
- `@protocol-debug "检查 TCP 7711 重连日志"` — 分析重连问题
- `@protocol-debug "生成一条模拟 UDP 7706 测试数据"` — 生成测试数据
