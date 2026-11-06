-- S3 课表导入：`reminder` 新增两列，都是「参与触发时刻计算、但 V3 没有承载位置」的输入。
-- 事实源：docs/02-design/storage.md §2.6（reminder）。本迁移尚未应用到任何环境（本地 flyway 历史停在 V4），
-- 因此改名与改内容都不产生校验和冲突。
--
-- exdate_dates —— 仅日期型 EXDATE（「整天停课」）需要与时刻型 EXDATE 分列存储。
-- 为什么不折算成时刻：date-only 排除的语义是「该当地日期内的所有 occurrence 都不触发」，
-- 而 RRULE 可以没有 COUNT/UNTIL（无限重复），折算只能在导入时穷举有限个 occurrence，
-- 未来的停课日会被漏掉——属于「看起来导入成功、实际算错」。按当地日期判重对无限规则同样成立。
--
-- lead_minutes —— 提醒相对 occurrence 的提前量，必须落列。
-- 为什么不能只在导入时用一次：COURSE 行的 dtstart 存的是**上课时刻**（否则「08:00 的课要能还原成 08:00」
-- 与 date-only 停课按当地日期判重都不成立），occurrence 序列因此定义在上课时刻上，
-- 而 next_trigger_at 物化的是 occurrence - lead。调度器每次触发后都要重算下一次 occurrence，
-- 若 lead 不落列，第二次及以后的提醒会退化成「踩点上课时刻提醒」；
-- 从行内反推也不可行了——反推出的提前量在 lead ≥ 相邻 occurrence 间隔时必然偏小
-- （例：每天 00:30 的课提前 60 分钟，反推得到 30 分钟）。
-- 默认 0 使 CUSTOM 与 DEADLINE 行（提醒时刻本身就是 occurrence）行为逐字不变。
--
-- 不新增索引：导入/预览的取数形状是
-- WHERE user_id = ? AND source_type = 'COURSE' AND source_uid IN (...)，
-- 现有 uk_reminder_user_source (user_id, source_type, source_uid) 的列序与之逐字对齐。

ALTER TABLE reminder
  ADD COLUMN lead_minutes INT UNSIGNED NOT NULL DEFAULT 0
    COMMENT '提醒提前量（分钟）；仅在 occurrence 定义于事件时刻本身的 COURSE 行上非零，重算下一次触发必须复用'
    AFTER timezone,
  ADD COLUMN exdate_dates JSON NULL
    COMMENT '仅日期型 EXDATE，存 ["YYYY-MM-DD", ...]；按 timezone 的当地日期判重，与 exdates 互补'
    AFTER exdates,
  ADD CONSTRAINT chk_reminder_exdate_dates CHECK (
    exdate_dates IS NULL OR JSON_TYPE(exdate_dates) = 'ARRAY'
  );
