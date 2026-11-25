-- S4 提醒触发历史与站内通知；事实源：docs/02-design/storage.md §2.16，契约面是 openapi-java.yaml 2.3.0 的 /api/v1/notifications。
-- 一行等于「一次已落库的触发」，通知只是它的 read_at 属性，因此不另建 notification 表（拆表得到一张逐行 1:1 的表，
-- 两次写必须同事务，否则出现「有触发没通知」这种无法解释的中间态）。
--
-- planned_trigger_at —— 列名刻意不叫 occurrence_start_at，虽然它取自事件的 occurrenceStartUtc 字段。
-- 该字段名是 S0 定型的遗留误名：ReminderDueService.dispatch() 传给它的实参是 row.nextTriggerAt()（截断到秒），
-- 而 lead_minutes 到 V7 才存在，所以课表行的这个值实际是「上课时刻 − 提前量」，即计划触发时刻。
-- 消费侧不回算上课时刻：一是载荷的 12 个字段里没有 lead_minutes，二是回读 reminder 行的当前 lead_minutes
-- 会把幂等键建在可变状态上——两次投递之间发生一次课表重导入就会算出两个不同值，唯一键挡不住重复通知。
--
-- 无外键指向 reminder —— 尽管同库同服务。加了之后，一条消息只要指向「已被清除或尚未可见」的提醒行就永久插入失败，
-- RocketMQ 的重投会一路走到死信，表现为「通知静默丢失」而不是「一次可恢复的重试」。归属由应用层保证：
-- user_id 直接取自事件载荷，而事件的 userId 又来自提醒行本身。本表也没有删除路径，因此不存在需要级联收敛的关系。
--
-- 不建 read_at 索引、也不并入复合索引：未读是低选择性的瞬态位，绝大多数历史行最终都会被读掉，
-- 而把它加进 (user_id, planned_trigger_at, id) 会破坏「列序与游标编码逐字同序」这一 keyset 分页前提。
-- 每用户行数上界为「课程数 × 学期周数 × 已触发周」，量级在几百行；超限时改为四列复合索引并登记为新切片（本期未压测，这是判据不是结论）。

CREATE TABLE reminder_trigger (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reminder_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NULL,
  source_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  planned_trigger_at DATETIME(3) NOT NULL,
  next_trigger_at DATETIME(3) NULL,
  trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  delivered_at DATETIME(3) NOT NULL,
  read_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_reminder_trigger_planned (reminder_id, planned_trigger_at)
    COMMENT '一次 occurrence 一行；RocketMQ at-least-once 重投的收敛点',
  KEY idx_reminder_trigger_user (user_id, planned_trigger_at, id)
    COMMENT '通知列表 keyset 分页：user_id 等值 + (planned_trigger_at, id) 降序',
  CONSTRAINT chk_reminder_trigger_source CHECK (
    source_type IN ('CUSTOM', 'DEADLINE', 'COURSE')
  ),
  CONSTRAINT chk_reminder_trigger_read CHECK (
    read_at IS NULL OR read_at >= delivered_at
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='提醒触发历史与站内通知；只由 reminder-service 读写';
