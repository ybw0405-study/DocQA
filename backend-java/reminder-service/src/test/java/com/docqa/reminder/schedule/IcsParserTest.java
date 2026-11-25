package com.docqa.reminder.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.recurrence.RruleParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ICS 解析器。判据只有一条：影响触发时刻的属性一律严格，其余一律忽略。
 * 因此本测试既验证「该拒的都拒了并且给出可定位的行号」，也验证「该放的绝不误拒」——
 * 后半部分是真实教务导出的存活条件（Google 与 Outlook 的文件里塞满被忽略的组件）。
 *
 * <p>行号断言都按 {@code event(...)} 助力的固定布局数出来：
 * 1 {@code BEGIN:VCALENDAR}、2 {@code VERSION:2.0}、3 {@code BEGIN:VEVENT}，属性从第 4 行起。
 */
class IcsParserTest {

    private static final ZoneId FALLBACK = ZoneId.of("Asia/Shanghai");
    private static final String UID = "linear-algebra-2026-fall-mon-0800";

    private final IcsParser parser = new IcsParser(new RruleParser(), new ReminderProperties());

    @Test
    void tzidFormKeepsTheClassMomentAndItsRegion() {
        IcsEvent event = parseOne(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"));

        assertThat(event.dtstart()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(event.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(event.rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
        assertThat(event.summary()).isEqualTo("线性代数");
        assertThat(event.sequence()).isZero();
    }

    @Test
    void utcFormStoresTheRegionNameNotTheOffsetLetter() {
        // ZoneOffset.UTC.getId() 的值是 "Z"；写进 timezone 列后 ZoneId.of("Z") 得到 ZoneOffset，
        // 会被「必须是 IANA 区名」的既有判据拒掉，重算下一次触发时整门课就 422。
        IcsEvent event = parseOne(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART:20260907T000000Z",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"));

        assertThat(event.timezone()).isEqualTo("UTC");
        // 下游「必须是 IANA 区名」的判据拒的就是 ZoneOffset，所以这里断言的是它没变成偏移量。
        assertThat(ZoneId.of(event.timezone())).isNotInstanceOf(ZoneOffset.class);
        assertThat(event.dtstart()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
    }

    @Test
    void floatingUsesTheRequestFallbackZoneInsteadOfGuessing() {
        IcsEvent event = parseOne(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART:20260907T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"));

        assertThat(event.dtstart()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(event.timezone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void oneOffEventWithoutRruleBecomesCountOneDaily() {
        IcsEvent event = parseOne(event(
                "UID:makeup-lecture",
                "SUMMARY:补课",
                "DTSTART;TZID=Asia/Shanghai:20261015T140000"));

        assertThat(event.rrule()).isEqualTo("FREQ=DAILY;COUNT=1");
        assertThat(event.rule().count()).isEqualTo(1);
    }

    @Test
    void foldedLinesUnfoldAndErrorsStillPointAtPhysicalLines() {
        List<String> folded = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RRULE:FREQ=WEEKLY",
                " ;BYDAY=MO",
                "END:VEVENT",
                "END:VCALENDAR"));
        assertThat(parseOne(ics(folded)).rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");

        // 同一个折行 RRULE 改成非法值：报错行号必须是折行展开前的第一物理行（7），客户端才定位得到。
        List<String> broken = new ArrayList<>(folded);
        broken.set(6, "RRULE:FREQ=weekly");
        expectFields(broken, "file.line.7");
    }

    @Test
    void googleStyleFileSurvivesIgnoredComponents() {
        IcsEvent event = parseOne(event(
                "PRODID:-//Google Inc//Google Calendar 70.9054//EN",
                "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH",
                "BEGIN:VTIMEZONE",
                "TZID:Asia/Shanghai",
                "BEGIN:STANDARD",
                "DTSTART:19700101T000000",
                "TZOFFSETFROM:+0800",
                "TZOFFSETTO:+0800",
                "END:STANDARD",
                "END:VTIMEZONE",
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "DTEND;TZID=Asia/Shanghai:20260907T094000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO",
                "LOCATION:三教 201",
                "STATUS:CONFIRMED",
                "X-GOOGLE-ID:abc",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT10M",
                "DESCRIPTION:提醒",
                "END:VALARM"));

        assertThat(event.uid()).isEqualTo(UID);
        assertThat(event.dtstart()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
    }

    @Test
    void singleLeadingBomIsAccepted() {
        byte[] body = ics(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"));
        byte[] withBom = new byte[3 + body.length];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(parseOne(withBom).uid()).isEqualTo(UID);
    }

    @Test
    void invalidUtf8IsRejectedWithoutReplacementCharacters() {
        // 0xFF 不是任何合法 UTF-8 序列的字节；解码失败后的字节无法确定行边界，因此就此中止。
        byte[] broken = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:".getBytes(
                StandardCharsets.UTF_8);
        byte[] withBadByte = new byte[broken.length + 1];
        System.arraycopy(broken, 0, withBadByte, 0, broken.length);
        withBadByte[broken.length] = (byte) 0xFF;

        assertThatThrownBy(() -> parser.parse(withBadByte, FALLBACK))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_001);
                    assertThat(ex.getFieldViolations()).singleElement().satisfies(violation -> {
                        assertThat(violation.field()).isEqualTo("file.line.4");
                        assertThat(violation.message()).contains("not valid UTF-8");
                    });
                });
    }

    @Test
    void rdateAndRecurrenceIdAreNamedAndRejected() {
        // 两者都会破坏「一行一规则」：RDATE 要与 RRULE 网格求并集，RECURRENCE-ID 表示单次改期。
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO",
                "RDATE:20260916T080000"), "file.line.8");
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RECURRENCE-ID;TZID=Asia/Shanghai:20260914T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"), "file.line.7");
    }

    @Test
    void duplicateUidIsReportedByIdentityWithBothLineNumbers() {
        String uidLine = "UID:" + UID;
        byte[] content = ics(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                uidLine,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "END:VEVENT",
                "BEGIN:VEVENT",
                uidLine,
                "SUMMARY:线性代数补班",
                "DTSTART;TZID=Asia/Shanghai:20260908T080000",
                "END:VEVENT",
                "END:VCALENDAR"));

        assertThatThrownBy(() -> parser.parse(content, FALLBACK))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getFieldViolations()).extracting(FieldViolation::field)
                            .containsExactly("file.uid." + UID);
                    assertThat(ex.getFieldViolations().get(0).message())
                            .contains("first VEVENT starts at line 3")
                            .contains("repeated at line 12");
                });
    }

    @Test
    void missingEndVeventReportsBothThePairAndTheStrayCalendarEnd() {
        expectFields(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "END:VCALENDAR"), "file.line.3", "file.line.7", "file.line.1");
    }

    @Test
    void foreignEndInsideVeventReportsTheMissingPairAndTheStrayEnd() {
        expectFields(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "END:VALARM",
                "END:VCALENDAR"), "file.line.3", "file.line.7");
    }

    @Test
    void nestedVeventIsRejectedRatherThanMerged() {
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "BEGIN:VEVENT"), "file.line.7");
    }

    @Test
    void secondVcalendarIsRejectedInsteadOfSilentlyDroppingItsEvents() {
        expectFields(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "END:VCALENDAR",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "END:VCALENDAR"), "file.line.4");
    }

    @Test
    void calendarEnvelopeMustBeCompleteAndVersionTwo() {
        expectFields(List.of(
                "BEGIN:VEVENT",
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "END:VEVENT"), "file.line.1");
        expectFields(List.of(
                "BEGIN:VCALENDAR",
                "BEGIN:VEVENT",
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "END:VEVENT",
                "END:VCALENDAR"), "file.line.1");
        expectFields(List.of("BEGIN:VCALENDAR", "VERSION:2.0"), "file.line.1");
        expectFields(List.of("BEGIN:VCALENDAR", "VERSION:1.0", "END:VCALENDAR"), "file.line.2");
    }

    @Test
    void eventCountCapIsFiveHundred() {
        List<String> lines = new ArrayList<>(List.of("BEGIN:VCALENDAR", "VERSION:2.0"));
        for (int index = 0; index < 501; index++) {
            lines.add("BEGIN:VEVENT");
            lines.add("UID:course-" + index);
            lines.add("SUMMARY:课程" + index);
            lines.add("DTSTART;TZID=Asia/Shanghai:20260907T080000");
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR");

        assertThatThrownBy(() -> parser.parse(ics(lines), FALLBACK))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_001);
                    assertThat(ex.getFieldViolations()).singleElement().satisfies(violation -> {
                        assertThat(violation.field()).isEqualTo("file.line.2507");
                        assertThat(violation.message()).contains("at most 500 VEVENT components");
                    });
                });
    }

    @Test
    void moreThanTwentyViolationsAreAllCollectedAndSummarisedInDetail() {
        // 上限 20 由 ProblemDetailFactory 施加（在控制器测试里取证）；解析器必须保留全部条目与总数，
        // 否则 detail 里的「共 N 处」会谎报。
        List<String> lines = new ArrayList<>(List.of("BEGIN:VCALENDAR", "VERSION:2.0"));
        for (int index = 0; index < 25; index++) {
            lines.add("BEGIN:VEVENT");
            lines.add("SUMMARY:缺 UID 的课程" + index);
            lines.add("DTSTART;TZID=Asia/Shanghai:20260907T080000");
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR");

        assertThatThrownBy(() -> parser.parse(ics(lines), FALLBACK))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getFieldViolations()).hasSize(25);
                    assertThat(ex.getDetail()).contains("共 25 处问题").contains("以下为前 20 处");
                });
    }

    @Test
    void allDayStartAndOffsetAndUnknownZonesAreRefused() {
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;VALUE=DATE:20260907",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"), "file.line.6", "file.line.3");
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=+08:00:20260907T080000"), "file.line.6", "file.line.3");
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Mars/Phobos:20260907T080000"), "file.line.6", "file.line.3");
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000Z"), "file.line.6", "file.line.3");
    }

    @Test
    void strictCalendarDatesRefuseImpossibleValues() {
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260231T080000"), "file.line.6", "file.line.3");
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:2026-09-07T080000"), "file.line.6", "file.line.3");
    }

    @Test
    void theTwoExdateShapesLandInDifferentColumns() {
        IcsEvent event = parseOne(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO",
                "EXDATE;VALUE=DATE:20261005,20261019",
                "EXDATE;TZID=Asia/Shanghai:20260914T080000"));

        assertThat(event.exdateDates()).containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 19));
        assertThat(event.exdates()).containsExactly(Instant.parse("2026-09-14T00:00:00Z"));
        assertThat(event.exdateValues()).containsExactly("2026-09-14T00:00:00Z", "2026-10-05", "2026-10-19");
    }

    @Test
    void exdateAppearingBeforeDtstartStillUsesTheEventsOwnZone() {
        // 教务导出里 EXDATE 常排在 DTSTART 之前；此时事件的时区还没确定，只能延后到 finish() 解析。
        IcsEvent event = parseOne(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "EXDATE:20260914T080000",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"));

        assertThat(event.exdates()).containsExactly(Instant.parse("2026-09-14T00:00:00Z"));
    }

    @Test
    void exdatePeriodAndEmptyTokenAreRefused() {
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "EXDATE;VALUE=PERIOD:20260914T080000/20260914T094000"), "file.line.7");
        // 两个空值同处一行且消息一致，去重后只占一条：errors 的 20 条预算要留给不同行的问题。
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "EXDATE:20260914T080000,,"), "file.line.7");
    }

    @Test
    void exdateCountFollowsTheConfiguredCap() {
        ReminderProperties capped = new ReminderProperties();
        capped.setMaxExdates(2);
        IcsParser limited = new IcsParser(new RruleParser(), capped);
        byte[] content = ics(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "EXDATE:20260914T080000,20260921T080000,20260928T080000"));

        assertThatThrownBy(() -> limited.parse(content, FALLBACK))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(
                        ex.getFieldViolations()).singleElement().satisfies(violation -> {
                            assertThat(violation.field()).isEqualTo("file.line.7");
                            assertThat(violation.message()).contains("at most 2 EXDATE values");
                        }));
    }

    @Test
    void uidMustBePrintableAsciiAndFitTheColumnItLandsIn() {
        // source_uid 是 ascii 列；放过非 ASCII 的 UID 会让 INSERT 变成 500，而不是可判读的 422。
        expectFields(event("UID:线性代数-0800",
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000"), "file.line.4", "file.line.3");
        expectFields(event("UID:" + "x".repeat(256),
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000"), "file.line.4", "file.line.3");
        expectFields(event("UID: ",
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000"), "file.line.4", "file.line.3");
    }

    @Test
    void overlongSummaryIsRefusedButOverlongDescriptionIsTruncated() {
        // SUMMARY 落在 VARCHAR(200)，超长必须拒；DESCRIPTION 不参与触发时刻，为它回滚整份课表不合理。
        expectFields(event("UID:" + UID,
                "SUMMARY:" + "课".repeat(201),
                "DTSTART;TZID=Asia/Shanghai:20260907T080000"), "file.line.5", "file.line.3");

        IcsEvent event = parseOne(event(
                "UID:" + UID,
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000",
                "DESCRIPTION:" + "说".repeat(1200)));
        assertThat(event.description()).hasSize(1000);
    }

    @Test
    void repeatedPropertyInsideOneVeventIsRefused() {
        expectFields(event("UID:" + UID,
                "UID:another",
                "SUMMARY:线性代数",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000"), "file.line.5");
    }

    @Test
    void sequenceIsComparedAsAnUnsignedInteger() {
        assertThat(parseOne(event("UID:" + UID,
                "SUMMARY:线性代数",
                "SEQUENCE:7",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000")).sequence()).isEqualTo(7);
        expectFields(event("UID:" + UID,
                "SUMMARY:线性代数",
                "SEQUENCE:-1",
                "DTSTART;TZID=Asia/Shanghai:20260907T080000"), "file.line.6");
    }

    private IcsEvent parseOne(byte[] content) {
        List<IcsEvent> events = parser.parse(content, FALLBACK);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private IcsEvent parseOne(List<String> lines) {
        return parseOne(ics(lines));
    }

    private void expectFields(List<String> lines, String... expectedFields) {
        assertThatThrownBy(() -> parser.parse(ics(lines), FALLBACK))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_001);
                    assertThat(ex.getFieldViolations()).extracting(FieldViolation::field)
                            .containsExactly(expectedFields);
                });
    }

    /** 在 {@code VCALENDAR} 信封里放一组 {@code VEVENT} 属性，物理行号与真实教务导出一致。 */
    private static List<String> event(String... eventLines) {
        List<String> lines = new ArrayList<>(List.of("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT"));
        lines.addAll(List.of(eventLines));
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");
        return lines;
    }

    private static byte[] ics(List<String> lines) {
        return String.join("\r\n", lines).concat("\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
