package com.docqa.reminder.service;

import com.docqa.reminder.repository.ReminderRepository;
import com.docqa.reminder.schedule.ScheduleDecision;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课表导入的写入边界。单独成 bean 的唯一理由：Spring 的事务代理只对「从别的 bean 调进来」的方法生效，
 * 把 {@code @Transactional} 写在 {@code ScheduleImportService} 自己的私有方法上等于没写。
 *
 * <p>整批一个事务：任一行的 SQL 异常都会让已插入的前几行一起回滚，
 * 因此不存在「半套课表已落库」的中间状态，这是验收判据「导入失败不得留下半套课表」的实现位置。
 */
@Service
public class ScheduleImportWriter {

    private final ReminderRepository reminderRepository;

    public ScheduleImportWriter(ReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    @Transactional
    public void persist(UUID tenantId, List<ScheduleDecision> decisions) {
        for (ScheduleDecision decision : decisions) {
            switch (decision.action()) {
                case CREATE -> reminderRepository.insert(tenantId, decision.row());
                case UPDATE -> reminderRepository.updateFromImport(tenantId, decision.row());
                case UNCHANGED, STALE -> {
                    // 判定为「不写」的事件不产生任何 SQL：UNCHANGED 是内容没变，STALE 是文件比库里更旧。
                }
            }
        }
    }
}
