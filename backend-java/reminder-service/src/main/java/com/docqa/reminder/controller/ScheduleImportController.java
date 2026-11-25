package com.docqa.reminder.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.reminder.dto.ScheduleImportData;
import com.docqa.reminder.dto.SchedulePreviewData;
import com.docqa.reminder.service.ImportScheduleResult;
import com.docqa.reminder.service.IcsUpload;
import com.docqa.reminder.service.ScheduleImportService;
import com.docqa.reminder.web.JwtAuthenticationFilter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ICS 课表的预览与导入入口。两个操作都是 {@code multipart/form-data}，
 * 且 {@code file} 之外的两个 part 是普通文本，所以参数约束直接写在方法签名上，
 * 形态非法（缺失、超长、超出范围）由框架拦下成 400，取值非法（未知时区）留给服务层判 422。
 *
 * <p>这里刻意不加 {@code @Validated}：Spring 6.1 的内建方法校验会走
 * {@code HandlerMethodValidationException}，{@code errors[].field} 是干净的参数名；
 * 加上 {@code @Validated} 改走 AOP 代理的 {@code ConstraintViolationException}，
 * field 会变成 {@code 方法名.参数名}，对调用方没有意义。
 *
 * <p>两个文本 Part 用 {@code @RequestParam} 而不是 {@code @RequestPart}：{@code @RequestPart}
 * 把除文件以外的 Part 一律交给 {@code HttpMessageConverter}，而 curl 与浏览器都不会给文本字段写
 * Part 级 Content-Type，没有任何转换器能把这种字段读成 {@code String} 或 {@code int}，
 * 于是每个请求都在绑定阶段抛出 {@code HttpMediaTypeNotSupportedException}——它表达的是
 * 「这个 Part 我读不了」，与调用方的错无关，全局策略会把它映射成 415
 * {@code COM-UNSUPPORTED_MEDIA-001}。
 * servlet 容器本来就把 multipart 里的文本字段暴露成请求参数，{@code @RequestParam} 才是对等的绑法。
 */
@RestController
@RequestMapping("/api/v1/reminders/ics")
public class ScheduleImportController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 与契约 {@code ScheduleImportRequest.fallbackTimezone.pattern} 逐字一致。 */
    private static final String TIMEZONE_PATTERN = "^UTC$|^[A-Za-z]+(?:/[A-Za-z0-9_+\\-]+)+$";

    /** 上限一周：提前一个学期提醒多半是误填，且它会直接放大 occurrence 的搜索区间。 */
    private static final int MAX_LEAD_MINUTES = 10080;

    private final ScheduleImportService scheduleImportService;

    public ScheduleImportController(ScheduleImportService scheduleImportService) {
        this.scheduleImportService = scheduleImportService;
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SchedulePreviewData> preview(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fallbackTimezone")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = TIMEZONE_PATTERN) String fallbackTimezone,
            @RequestParam("leadMinutes")
            @Min(0)
            @Max(MAX_LEAD_MINUTES) int leadMinutes) throws IOException {
        return ApiResponse.ok(scheduleImportService.preview(
                userId, idempotencyKey, toUpload(file), fallbackTimezone, leadMinutes));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ScheduleImportData>> importSchedule(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fallbackTimezone")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = TIMEZONE_PATTERN) String fallbackTimezone,
            @RequestParam("leadMinutes")
            @Min(0)
            @Max(MAX_LEAD_MINUTES) int leadMinutes) throws IOException {
        ImportScheduleResult result = scheduleImportService.importSchedule(
                userId, idempotencyKey, toUpload(file), fallbackTimezone, leadMinutes);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.data()));
    }

    private static IcsUpload toUpload(MultipartFile file) throws IOException {
        return new IcsUpload(file.getContentType(), file.getBytes());
    }
}
