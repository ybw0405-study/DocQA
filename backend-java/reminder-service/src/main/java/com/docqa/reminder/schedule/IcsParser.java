package com.docqa.reminder.schedule;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.recurrence.ReminderRuleException;
import com.docqa.reminder.recurrence.Rrule;
import com.docqa.reminder.recurrence.RruleParser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * ICS 课表解析器。判据只有一条：<strong>影响触发时刻的属性一律严格，其余一律忽略</strong>。
 *
 * <ul>
 *   <li>严格：{@code UID}、{@code DTSTART}、{@code RRULE}、{@code EXDATE}、{@code SEQUENCE}
 *       的形态与取值非法即 422；{@code RDATE} 与 {@code RECURRENCE-ID} 会改变 occurrence 集合但本服务
 *       不展开它们，因此点名拒绝（附属性名与物理行号），而不是静默忽略后算出一套错课表。</li>
 *   <li>忽略：{@code VTIMEZONE}、{@code VALARM}、{@code LOCATION}、{@code DTEND}/{@code DURATION}、
 *       {@code PRODID}、{@code CALSCALE}、{@code METHOD}、{@code X-*} 等。教务导出文件里这些组件极多，
 *       逐条拒绝会让导入永远走不通，而它们不参与触发时刻。{@code LOCATION} 丢失已在 CR 登记。</li>
 * </ul>
 *
 * <p>文本按 UTF-8 <strong>逐物理行严格解码</strong>：允许一个起始 BOM，出现非法字节序列直接拒绝，
 * 不降级成 U+FFFD——替换字符会让课程名带病落库，之后再也无法区分源文件乱码与解码错误。
 * 行边界只按裸 ASCII 的 {@code CR}/{@code LF} 切分：UTF-8 多字节序列的首字节均 ≥ 0x80，
 * 所以「先切行、后逐行解码」不会把一个字符劈成两半。
 *
 * <p>折行（CRLF + 单个空格/制表符）在判定前展开，但报错用的行号始终是<strong>折行展开前</strong>的物理行号，
 * 客户端可以据此在文本里定位。结构缺陷一次性收集完再抛：最多报告 {@value #MAX_REPORTED_ERRORS} 条
 * （与 {@code ProblemDetail.errors} 的 maxItems 同值），总条数写进 {@code detail}。
 */
@Component
public class IcsParser {

    private static final int MAX_EVENTS = 500;
    private static final int MAX_UID_LENGTH = 255;
    private static final int MAX_SUMMARY_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_REPORTED_ERRORS = 20;

    private static final String BEGIN = "BEGIN";
    private static final String END = "END";
    private static final String VCALENDAR = "VCALENDAR";
    private static final String VEVENT = "VEVENT";
    private static final String VERSION = "VERSION";
    private static final String ONE_OFF_RRULE = "FREQ=DAILY;COUNT=1";
    private static final String SUPPORTED_VERSION = "2.0";

    /**
     * 存 {@code "UTC"} 而不是 {@code ZoneOffset.UTC.getId()}：后者的值是 {@code "Z"}，
     * 落到 {@code reminder.timezone} 后 {@code ZoneId.of("Z")} 得到 ZoneOffset，
     * 会被「必须是 IANA 区名」这条既有判据拒掉，重算下一次触发时就会 422。
     */
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final Set<String> REJECTED_EVENT_PROPERTIES = Set.of("RDATE", "RECURRENCE-ID");
    private static final Pattern PROPERTY_NAME = Pattern.compile("^[A-Z][A-Z0-9-]*$");
    private static final Pattern BASIC_DATE = Pattern.compile("^\\d{8}$");
    private static final Pattern BASIC_DATE_TIME = Pattern.compile("^\\d{8}T\\d{6}$");
    private static final Pattern BASIC_UTC_DATE_TIME = Pattern.compile("^\\d{8}T\\d{6}Z$");

    /** STRICT + {@code uuuu}：非法日期（如 2 月 31 日）与位数不足一律解析失败，不做任何宽容降级。 */
    private static final DateTimeFormatter BASIC_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter BASIC_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT);

    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final RruleParser rruleParser;
    private final ReminderProperties properties;

    public IcsParser(RruleParser rruleParser, ReminderProperties properties) {
        this.rruleParser = rruleParser;
        this.properties = properties;
    }

    public List<IcsEvent> parse(byte[] content, ZoneId fallbackZone) {
        List<FieldViolation> violations = new ArrayList<>();
        List<String> physicalLines = new ArrayList<>();
        if (!decode(content, physicalLines, violations)) {
            // 解码失败点之后的字节无法确定行边界，继续扫描只会产出成串假错误，因此就此中止。
            throw structureError(violations);
        }
        List<IcsEvent> events = assemble(unfold(physicalLines, violations), fallbackZone, violations);
        if (!violations.isEmpty()) {
            throw structureError(violations);
        }
        return events;
    }

    /**
     * 逐物理行严格 UTF-8 解码。
     *
     * @return false 表示遇到非法字节序列，调用方必须中止
     */
    private boolean decode(byte[] content, List<String> lines, List<FieldViolation> violations) {
        byte[] bytes = stripBom(content);
        int start = 0;
        int lineNumber = 1;
        while (start < bytes.length) {
            int end = start;
            while (end < bytes.length && bytes[end] != CR && bytes[end] != LF) {
                end++;
            }
            if (!decodeLine(bytes, start, end, lineNumber, lines, violations)) {
                return false;
            }
            if (end == bytes.length) {
                break;
            }
            start = (bytes[end] == CR && end + 1 < bytes.length && bytes[end + 1] == LF) ? end + 2 : end + 1;
            lineNumber++;
        }
        return true;
    }

    private boolean decodeLine(
            byte[] bytes,
            int from,
            int to,
            int lineNumber,
            List<String> lines,
            List<FieldViolation> violations) {
        try {
            lines.add(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, from, to - from))
                    .toString());
            return true;
        } catch (CharacterCodingException ex) {
            violations.add(lineError(
                    lineNumber, "line is not valid UTF-8; re-save the file as UTF-8"));
            return false;
        }
    }

    /** 展开 RFC 5545 折行，并把每个逻辑行拆成属性名、参数与值。 */
    private List<LogicalLine> unfold(List<String> physicalLines, List<FieldViolation> violations) {
        List<LogicalLine> lines = new ArrayList<>(physicalLines.size());
        StringBuilder pending = null;
        int pendingLine = 0;
        for (int index = 0; index < physicalLines.size(); index++) {
            String raw = physicalLines.get(index);
            int number = index + 1;
            boolean continuation = !raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t');
            if (continuation) {
                if (pending == null) {
                    violations.add(lineError(number, "folded line has no preceding line to continue"));
                    continue;
                }
                pending.append(raw, 1, raw.length());
                continue;
            }
            if (pending != null) {
                addIfProperty(pending.toString(), pendingLine, lines, violations);
            }
            pending = new StringBuilder(raw);
            pendingLine = number;
        }
        if (pending != null) {
            addIfProperty(pending.toString(), pendingLine, lines, violations);
        }
        return lines;
    }

    private void addIfProperty(String text, int lineNumber, List<LogicalLine> lines, List<FieldViolation> violations) {
        if (text.isBlank()) {
            return;
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            violations.add(lineError(lineNumber, "line is not a property (missing ':')"));
            return;
        }
        String[] segments = text.substring(0, colon).split(";", -1);
        String name = segments[0].trim().toUpperCase(Locale.ROOT);
        if (!PROPERTY_NAME.matcher(name).matches()) {
            violations.add(lineError(lineNumber, "invalid property name: " + segments[0]));
            return;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 1; index < segments.length; index++) {
            int equals = segments[index].indexOf('=');
            if (equals <= 0) {
                violations.add(lineError(
                        lineNumber, "property parameter must use NAME=VALUE: " + segments[index]));
                continue;
            }
            String key = segments[index].substring(0, equals).trim().toUpperCase(Locale.ROOT);
            String value = trimQuotes(segments[index].substring(equals + 1));
            if (!PROPERTY_NAME.matcher(key).matches() || value.isEmpty()) {
                violations.add(lineError(lineNumber, "invalid property parameter: " + segments[index]));
                continue;
            }
            parameters.putIfAbsent(key, value);
        }
        lines.add(new LogicalLine(name, parameters, text.substring(colon + 1), lineNumber));
    }

    private List<IcsEvent> assemble(List<LogicalLine> lines, ZoneId fallbackZone, List<FieldViolation> violations) {
        List<IcsEvent> events = new ArrayList<>();
        Set<String> seenUids = new LinkedHashSet<>();
        Map<String, Integer> eventLineOfUid = new LinkedHashMap<>();
        int firstLine = lines.isEmpty() ? 1 : lines.get(0).lineNumber();
        EventBuilder event = null;
        SkippedComponent skipped = null;
        boolean calendarStarted = false;
        boolean calendarEnded = false;
        boolean versionSeen = false;
        boolean overLimitReported = false;
        int calendarLine = 0;

        for (LogicalLine line : lines) {
            if (skipped != null) {
                skipped.accept(line);
                if (skipped.finished()) {
                    skipped = null;
                }
                continue;
            }
            if (BEGIN.equals(line.name())) {
                String component = componentOf(line);
                if (VCALENDAR.equals(component)) {
                    if (calendarStarted) {
                        violations.add(lineError(
                                line.lineNumber(), "only one VCALENDAR component is supported"));
                        continue;
                    }
                    calendarStarted = true;
                    calendarLine = line.lineNumber();
                } else if (VEVENT.equals(component)) {
                    if (event != null) {
                        violations.add(lineError(line.lineNumber(), "VEVENT cannot nest another VEVENT"));
                        continue;
                    }
                    event = new EventBuilder(line.lineNumber(), fallbackZone);
                } else {
                    skipped = new SkippedComponent();
                }
                continue;
            }
            if (END.equals(line.name())) {
                String component = componentOf(line);
                if (event != null && !VEVENT.equals(component)) {
                    violations.add(missingEndEvent(event));
                    violations.add(lineError(line.lineNumber(),
                            "unexpected END:" + component + " inside VEVENT"));
                    event = null;
                    continue;
                }
                if (event != null) {
                    if (events.size() < MAX_EVENTS) {
                        acceptEvent(event, line, violations, events, seenUids, eventLineOfUid);
                    } else if (!overLimitReported) {
                        violations.add(lineError(line.lineNumber(),
                                "a schedule file may contain at most " + MAX_EVENTS + " VEVENT components"));
                        overLimitReported = true;
                    }
                    event = null;
                    continue;
                }
                if (calendarStarted && VCALENDAR.equals(component)) {
                    calendarEnded = true;
                    continue;
                }
                violations.add(lineError(line.lineNumber(), "unexpected END:" + component));
                continue;
            }
            if (event != null) {
                event.accept(line, violations);
                continue;
            }
            if (!calendarStarted) {
                continue;
            }
            if (VERSION.equals(line.name())) {
                versionSeen = true;
                if (!SUPPORTED_VERSION.equals(line.value().trim())) {
                    violations.add(lineError(line.lineNumber(), "unsupported VERSION: " + line.value().trim()));
                }
            }
        }

        if (event != null) {
            violations.add(missingEndEvent(event));
        }
        if (!calendarStarted) {
            violations.add(lineError(firstLine, "missing BEGIN:VCALENDAR"));
        } else {
            if (!versionSeen) {
                violations.add(lineError(calendarLine, "VCALENDAR is missing required VERSION property"));
            }
            if (!calendarEnded) {
                violations.add(lineError(calendarLine, "missing END:VCALENDAR"));
            }
        }
        return events;
    }

    private void acceptEvent(
            EventBuilder event,
            LogicalLine endLine,
            List<FieldViolation> violations,
            List<IcsEvent> events,
            Set<String> seenUids,
            Map<String, Integer> eventLineOfUid) {
        IcsEvent finished = event.finish(violations);
        if (finished == null) {
            return;
        }
        if (!seenUids.add(finished.uid())) {
            violations.add(violation(
                    "file.uid." + finished.uid(),
                    "duplicate UID " + finished.uid() + " (first VEVENT starts at line "
                            + eventLineOfUid.getOrDefault(finished.uid(), 0) + ", repeated at line "
                            + endLine.lineNumber() + ")"));
            return;
        }
        eventLineOfUid.put(finished.uid(), event.startLine);
        events.add(finished);
    }

    private BusinessException structureError(List<FieldViolation> violations) {
        // 同一行的重复条目（例如一个 EXDATE 列表里有两个空值）先去重：errors 上限 20 是稀缺预算，
        // 让重复条目占位会把其它行的不同问题挤到 detail 的汇总数字里而客户端看不见。
        List<FieldViolation> distinct = violations.stream().distinct().toList();
        int total = distinct.size();
        String detail = "ICS 解析失败，共 " + total + " 处问题，整批未导入。"
                + (total > MAX_REPORTED_ERRORS ? "以下为前 " + MAX_REPORTED_ERRORS + " 处。" : "");
        return new BusinessException(ErrorCode.REM_VALIDATION_001, detail, distinct);
    }

    private static FieldViolation missingEndEvent(EventBuilder event) {
        String uid = event.uid == null ? "(unknown)" : event.uid;
        return lineError(event.startLine, "missing END:VEVENT for UID " + uid);
    }

    private static FieldViolation lineError(int lineNumber, String message) {
        return FieldViolation.of("file.line." + lineNumber, message);
    }

    private static FieldViolation violation(String field, String message) {
        return FieldViolation.of(field, message);
    }

    private static String componentOf(LogicalLine line) {
        return line.value().trim().toUpperCase(Locale.ROOT);
    }

    private static byte[] stripBom(byte[] content) {
        if (content.length >= UTF_8_BOM.length
                && content[0] == UTF_8_BOM[0]
                && content[1] == UTF_8_BOM[1]
                && content[2] == UTF_8_BOM[2]) {
            byte[] stripped = new byte[content.length - UTF_8_BOM.length];
            System.arraycopy(content, UTF_8_BOM.length, stripped, 0, stripped.length);
            return stripped;
        }
        return content;
    }

    private static String trimQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isPrintableAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x20 || current > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private record LogicalLine(String name, Map<String, String> parameters, String value, int lineNumber) {
    }

    /** EXDATE 的原始 token：时刻级要等 {@code DTSTART} 定下事件时区后才能解释，因此先原样存下。 */
    private record RawExdate(String value, String zoneId, int lineNumber, boolean dated) {

        private static RawExdate moment(String value, String zoneId, int lineNumber) {
            return new RawExdate(value, zoneId, lineNumber, false);
        }

        private static RawExdate dated(String value, int lineNumber) {
            return new RawExdate(value, null, lineNumber, true);
        }
    }

    /** 被忽略组件（VTIMEZONE、VALARM 等）的整棵子树：不解析内容，但必须等到配对的 END 才恢复判定。 */
    private static final class SkippedComponent {

        private int depth = 1;

        private void accept(LogicalLine line) {
            if (BEGIN.equals(line.name())) {
                depth++;
            } else if (END.equals(line.name())) {
                depth--;
            }
        }

        private boolean finished() {
            return depth == 0;
        }
    }

    private final class EventBuilder {

        private final int startLine;
        private final ZoneId fallbackZone;
        private final List<RawExdate> rawExdates = new ArrayList<>();
        private final Set<String> seenProperties = new LinkedHashSet<>();
        private String uid;
        private String summary;
        private String description;
        private Integer sequence;
        private String rruleText;
        private Rrule rule;
        private Instant dtstart;
        private ZoneId zone;

        private EventBuilder(int startLine, ZoneId fallbackZone) {
            this.startLine = startLine;
            this.fallbackZone = fallbackZone;
        }

        private void accept(LogicalLine line, List<FieldViolation> violations) {
            if (REJECTED_EVENT_PROPERTIES.contains(line.name())) {
                violations.add(lineError(
                        line.lineNumber(),
                        "unsupported property " + line.name() + " is rejected, use RRULE with EXDATE"));
                return;
            }
            switch (line.name()) {
                case "UID" -> setOnce(line, violations, () -> uid = readUid(line, violations));
                case "SUMMARY" -> setOnce(line, violations, () -> summary = readSummary(line, violations));
                case "DESCRIPTION" -> setOnce(line, violations, () -> description = readDescription(line.value()));
                case "SEQUENCE" -> setOnce(line, violations, () -> sequence = readSequence(line, violations));
                case "DTSTART" -> setOnce(line, violations, () -> readDtstart(line, violations));
                case "RRULE" -> setOnce(line, violations, () -> readRrule(line, violations));
                case "EXDATE" -> readExdate(line, violations);
                default -> {
                    // DTEND、DURATION、LOCATION、STATUS、X-* 等不参与触发时刻计算，忽略。
                }
            }
        }

        private void setOnce(LogicalLine line, List<FieldViolation> violations, Runnable setter) {
            if (!seenProperties.add(line.name())) {
                violations.add(lineError(
                        line.lineNumber(), line.name() + " must not appear more than once in a VEVENT"));
                return;
            }
            setter.run();
        }

        private String readUid(LogicalLine line, List<FieldViolation> violations) {
            String value = line.value().trim();
            if (value.isEmpty()) {
                violations.add(lineError(line.lineNumber(), "UID must not be blank"));
                return null;
            }
            if (value.length() > MAX_UID_LENGTH) {
                violations.add(lineError(line.lineNumber(), "UID exceeds " + MAX_UID_LENGTH + " characters"));
                return null;
            }
            if (!isPrintableAscii(value)) {
                violations.add(lineError(line.lineNumber(),
                        "UID must be printable ASCII; source_uid is stored in an ASCII-only column"));
                return null;
            }
            return value;
        }

        private String readSummary(LogicalLine line, List<FieldViolation> violations) {
            String value = line.value().trim();
            if (value.isEmpty()) {
                violations.add(lineError(line.lineNumber(), "SUMMARY must not be blank"));
                return null;
            }
            if (value.length() > MAX_SUMMARY_LENGTH) {
                violations.add(lineError(line.lineNumber(), "SUMMARY exceeds " + MAX_SUMMARY_LENGTH
                        + " characters; shorten the course name in the source file"));
                return null;
            }
            return value;
        }

        private String readDescription(String value) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            // 截断而不报错：DESCRIPTION 不参与触发时刻计算，为超长的描述回滚整份课表不合理。
            return trimmed.length() > MAX_DESCRIPTION_LENGTH
                    ? trimmed.substring(0, MAX_DESCRIPTION_LENGTH)
                    : trimmed;
        }

        private Integer readSequence(LogicalLine line, List<FieldViolation> violations) {
            String rejection = "SEQUENCE must be a non-negative integer";
            try {
                int value = Integer.parseInt(line.value().trim());
                if (value < 0) {
                    violations.add(lineError(line.lineNumber(), rejection));
                    return null;
                }
                return value;
            } catch (NumberFormatException ex) {
                violations.add(lineError(line.lineNumber(), rejection));
                return null;
            }
        }

        private void readDtstart(LogicalLine line, List<FieldViolation> violations) {
            String value = line.value().trim();
            String tzid = line.parameters().get("TZID");
            String valueType = line.parameters().get("VALUE");
            for (String parameter : line.parameters().keySet()) {
                if (!"TZID".equals(parameter) && !"VALUE".equals(parameter)) {
                    violations.add(lineError(
                            line.lineNumber(), "unsupported DTSTART parameter: " + parameter));
                    return;
                }
            }
            if (valueType != null && !"DATE-TIME".equals(valueType)) {
                violations.add(lineError(line.lineNumber(),
                        "unsupported DTSTART VALUE: " + valueType + "; a course must carry a start time"));
                return;
            }
            if (BASIC_DATE.matcher(value).matches()) {
                violations.add(lineError(line.lineNumber(),
                        "all-day DTSTART is not supported; a course needs a start time"));
                return;
            }
            if (BASIC_UTC_DATE_TIME.matcher(value).matches()) {
                if (tzid != null) {
                    violations.add(lineError(line.lineNumber(), "DTSTART ending with Z must not carry TZID"));
                    return;
                }
                LocalDateTime parsed = parseDateTime(value.substring(0, value.length() - 1), line, violations);
                if (parsed != null) {
                    zone = UTC;
                    dtstart = parsed.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
                }
                return;
            }
            if (!BASIC_DATE_TIME.matcher(value).matches()) {
                violations.add(lineError(line.lineNumber(),
                        "DTSTART must be YYYYMMDDTHHMMSS with an optional trailing Z: " + value));
                return;
            }
            // 无 TZID 也不以 Z 结尾 = floating：一律用请求里的 fallbackTimezone 解释，不猜调用方所在区。
            // fallbackZone 已由调用方按「必须是 IANA 区名」校验，此处只做 TZID 的准入。
            ZoneId resolved = tzid == null ? fallbackZone : resolveZone(tzid, line, violations);
            if (resolved == null) {
                return;
            }
            LocalDateTime parsed = parseDateTime(value, line, violations);
            if (parsed == null) {
                return;
            }
            zone = resolved;
            dtstart = parsed.atZone(resolved).toInstant().truncatedTo(ChronoUnit.SECONDS);
        }

        private void readRrule(LogicalLine line, List<FieldViolation> violations) {
            String value = line.value().trim();
            try {
                rule = rruleParser.parse(value);
                rruleText = value;
            } catch (ReminderRuleException ex) {
                violations.add(lineError(line.lineNumber(), "RRULE is invalid: " + ex.getMessage()));
            }
        }

        private void readExdate(LogicalLine line, List<FieldViolation> violations) {
            String valueType = line.parameters().get("VALUE");
            if (valueType != null && !"DATE".equals(valueType) && !"DATE-TIME".equals(valueType)) {
                violations.add(lineError(line.lineNumber(), "unsupported EXDATE VALUE: " + valueType
                        + "; VALUE=PERIOD must be listed as separate cancelled occurrences"));
                return;
            }
            for (String token : line.value().split(",", -1)) {
                String value = token.trim();
                if (value.isEmpty()) {
                    violations.add(lineError(line.lineNumber(), "EXDATE list contains an empty value"));
                    continue;
                }
                boolean dateOnly = "DATE".equals(valueType)
                        || (valueType == null && BASIC_DATE.matcher(value).matches());
                if (dateOnly && !BASIC_DATE.matcher(value).matches()) {
                    violations.add(lineError(line.lineNumber(),
                            "EXDATE;VALUE=DATE must be YYYYMMDD: " + value));
                    continue;
                }
                rawExdates.add(dateOnly
                        ? RawExdate.dated(value, line.lineNumber())
                        : RawExdate.moment(value, line.parameters().get("TZID"), line.lineNumber()));
            }
            if (rawExdates.size() > properties.getMaxExdates()) {
                violations.add(lineError(line.lineNumber(),
                        "a VEVENT may carry at most " + properties.getMaxExdates() + " EXDATE values"));
            }
        }

        private IcsEvent finish(List<FieldViolation> violations) {
            if (uid == null) {
                violations.add(lineError(startLine, "VEVENT requires a valid UID"));
                return null;
            }
            if (summary == null) {
                violations.add(lineError(startLine, "VEVENT requires a non-blank SUMMARY"));
                return null;
            }
            if (dtstart == null) {
                violations.add(lineError(startLine, "VEVENT requires DTSTART"));
                return null;
            }
            if (rruleText == null) {
                // 无 RRULE 的单个事件 = 一次性提醒：COUNT=1 让 occurrence 集合恰好只有 dtstart。
                rruleText = ONE_OFF_RRULE;
                rule = Rrule.dailyOnce();
            }
            List<Instant> moments = new ArrayList<>();
            List<LocalDate> dates = new ArrayList<>();
            for (RawExdate raw : rawExdates) {
                if (raw.dated()) {
                    LocalDate parsed = parseDate(raw.value());
                    if (parsed == null) {
                        violations.add(lineError(raw.lineNumber(),
                                "EXDATE is not a valid ICS date: " + raw.value()));
                    } else if (!dates.contains(parsed)) {
                        dates.add(parsed);
                    }
                    continue;
                }
                Instant parsed = parseMoment(raw, violations);
                if (parsed != null && !moments.contains(parsed)) {
                    moments.add(parsed);
                }
            }
            return new IcsEvent(
                    uid,
                    summary,
                    description,
                    sequence == null ? 0 : sequence,
                    rruleText,
                    rule,
                    dtstart,
                    zone.getId(),
                    moments.stream().sorted().toList(),
                    dates.stream().sorted().toList());
        }

        private Instant parseMoment(RawExdate raw, List<FieldViolation> violations) {
            String value = raw.value();
            ZoneId resolved;
            String local;
            if (BASIC_UTC_DATE_TIME.matcher(value).matches()) {
                resolved = UTC;
                local = value.substring(0, value.length() - 1);
            } else if (BASIC_DATE_TIME.matcher(value).matches()) {
                resolved = raw.zoneId() == null ? zone : resolveZone(raw.zoneId(), raw.lineNumber(), violations);
                if (resolved == null) {
                    return null;
                }
                local = value;
            } else {
                violations.add(lineError(raw.lineNumber(),
                        "EXDATE must be YYYYMMDD, YYYYMMDDTHHMMSS or YYYYMMDDTHHMMSSZ: " + value));
                return null;
            }
            LocalDateTime parsed = parseDateTime(local, raw.lineNumber(), violations);
            if (parsed == null) {
                return null;
            }
            return parsed.atZone(resolved).toInstant().truncatedTo(ChronoUnit.SECONDS);
        }

        private LocalDate parseDate(String value) {
            try {
                return LocalDate.parse(value, BASIC_DATE_FORMAT);
            } catch (DateTimeParseException ex) {
                return null;
            }
        }

        private LocalDateTime parseDateTime(String value, LogicalLine line, List<FieldViolation> violations) {
            return parseDateTime(value, line.lineNumber(), violations);
        }

        private LocalDateTime parseDateTime(String value, int lineNumber, List<FieldViolation> violations) {
            try {
                return LocalDateTime.parse(value, BASIC_DATE_TIME_FORMAT);
            } catch (DateTimeParseException ex) {
                violations.add(lineError(lineNumber, "value is not a valid ICS date-time: " + value));
                return null;
            }
        }

        private ZoneId resolveZone(String value, LogicalLine line, List<FieldViolation> violations) {
            return resolveZone(value, line.lineNumber(), violations);
        }

        private ZoneId resolveZone(String value, int lineNumber, List<FieldViolation> violations) {
            try {
                ZoneId candidate = ZoneId.of(value);
                if (candidate instanceof ZoneOffset) {
                    violations.add(lineError(lineNumber,
                            "TZID must be an IANA region such as Asia/Shanghai: " + value));
                    return null;
                }
                return candidate;
            } catch (DateTimeException ex) {
                violations.add(lineError(lineNumber, "unknown IANA timezone: " + value));
                return null;
            }
        }
    }
}
