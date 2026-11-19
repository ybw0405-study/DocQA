package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.domain.AppUserRow;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.UserListData;
import com.docqa.user.dto.UserSummaryData;
import com.docqa.user.repository.RoleRepository;
import com.docqa.user.repository.UserRepository;
import com.docqa.user.support.InMemoryIdempotencyStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID FOREIGN_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";

    private MutableClock clock;
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private RefreshTokenService refreshTokenService;
    private AccessTokenAuthenticator accessTokenAuthenticator;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        accessTokenAuthenticator = mock(AccessTokenAuthenticator.class);
        service = new AdminUserService(
                userRepository,
                roleRepository,
                refreshTokenService,
                accessTokenAuthenticator,
                new IdempotencyGuard(
                        new InMemoryIdempotencyStore(), new ObjectMapper().findAndRegisterModules(), clock),
                clock);
    }

    @Test
    void listAsksForOneExtraRowAndReturnsTheNextCursorOfTheLastReturnedRow() {
        AppUserRow first = row(OTHER, NOW);
        AppUserRow second = row(TARGET, NOW.minusSeconds(1));
        AppUserRow third = row(CALLER, NOW.minusSeconds(2));
        when(userRepository.findPage(TENANT_ID, null, null, null, null, 3))
                .thenReturn(List.of(first, second, third));
        when(roleRepository.findRolesByUserIds(TENANT_ID, List.of(OTHER, TARGET)))
                .thenReturn(Map.of(OTHER, Set.of(UserRole.USER)));

        UserListData page = service.list(TENANT_ID, null, null, null, 2);

        assertThat(page.items()).extracting(UserSummaryData::userId).containsExactly(OTHER, TARGET);
        assertThat(page.items().get(0).roles()).containsExactly(UserRole.USER);
        assertThat(page.items().get(1).roles()).isEmpty();
        assertThat(page.limit()).isEqualTo(2);
        assertThat(KeysetCursor.decode(page.nextCursor()))
                .hasValueSatisfying(cursor -> {
                    assertThat(cursor.id()).isEqualTo(TARGET);
                    assertThat(cursor.sortKey()).isEqualTo(second.createdAt());
                });
        verify(roleRepository, times(1)).findRolesByUserIds(any(), any());
    }

    @Test
    void listOmitsTheCursorWhenThePageIsExactlyFull() {
        when(userRepository.findPage(TENANT_ID,
                UserStatus.ACTIVE, UserRole.ADMIN, null, null, 3))
                .thenReturn(List.of(row(TARGET, NOW), row(OTHER, NOW.minusSeconds(1))));

        UserListData page = service.list(TENANT_ID, UserStatus.ACTIVE, UserRole.ADMIN, null, 2);

        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).hasSize(2);
    }

    @Test
    void listPassesTheDecodedCursorToTheRepository() {
        String cursor = KeysetCursor.of(NOW, TARGET).encode();
        when(userRepository.findPage(TENANT_ID, UserStatus.ACTIVE, null, NOW, TARGET, 21))
                .thenReturn(List.of());

        service.list(TENANT_ID, UserStatus.ACTIVE, null, cursor, 20);

        verify(userRepository).findPage(
                TENANT_ID, UserStatus.ACTIVE, null, NOW, TARGET, 21);
    }

    @Test
    void listRejectsOutOfRangePageSize() {
        assertThatThrownBy(() -> service.list(TENANT_ID, null, null, null, 0))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.field()).isEqualTo("limit"));
                });
        assertThatThrownBy(() -> service.list(TENANT_ID, null, null, null, 101))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001));
        verify(userRepository, never()).findPage(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void listRejectsMalformedCursor() {
        assertThatThrownBy(() -> service.list(TENANT_ID, null, null, "not-a-cursor", 20))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.field()).isEqualTo("cursor"));
                });
        verify(userRepository, never()).findPage(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void getReturnsRolesOfTheTargetUser() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW.minusSeconds(30))));
        when(roleRepository.findRoles(TENANT_ID, TARGET)).thenReturn(Set.of(UserRole.USER, UserRole.ADMIN));

        UserSummaryData summary = service.get(TENANT_ID, TARGET);

        assertThat(summary.userId()).isEqualTo(TARGET);
        assertThat(summary.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(summary.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void getRejectsUnknownUser() {
        assertThatThrownBy(() -> service.get(TENANT_ID, TARGET))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_NOT_FOUND_001));
    }

    @Test
    void replaceRolesRejectsAnEmptyRoleSet() {
        assertThatThrownBy(() -> service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.field()).isEqualTo("roles"));
                });
        verify(roleRepository, never()).replaceRoles(any(), any(), any(), any());
    }

    @Test
    void replaceRolesRejectsRemovingAdminFromTheCaller() {
        assertThatThrownBy(() -> service.replaceRoles(TENANT_ID, CALLER, CALLER, IDEMPOTENCY_KEY, Set.of(UserRole.USER)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.message()).contains("ADMIN"));
                });
        verify(roleRepository, never()).replaceRoles(any(), any(), any(), any());
    }

    @Test
    void replaceRolesRejectsUnknownTarget() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_NOT_FOUND_001));
        verify(roleRepository, never()).replaceRoles(any(), any(), any(), any());
    }

    @Test
    void replaceRolesWritesOnceAndReturnsTheFreshRoles() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW)));
        when(roleRepository.findRoles(TENANT_ID, TARGET)).thenReturn(Set.of(UserRole.ADMIN));

        UserSummaryData summary =
                service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN));

        assertThat(summary.roles()).containsExactly(UserRole.ADMIN);
        verify(roleRepository).replaceRoles(
                TENANT_ID, TARGET, Set.of(UserRole.ADMIN), NOW);
    }

    @Test
    void replaceRolesReplayDoesNotWriteAgain() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW)));
        when(roleRepository.findRoles(TENANT_ID, TARGET)).thenReturn(Set.of(UserRole.ADMIN));

        UserSummaryData first =
                service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN));
        UserSummaryData replay =
                service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN));

        assertThat(replay.userId()).isEqualTo(first.userId());
        assertThat(replay.roles()).containsExactly(UserRole.ADMIN);
        verify(roleRepository, times(1)).replaceRoles(any(), any(), any(), any());
        // 第一次调用会读两次（先校验目标存在，再读回最新角色），重放不得再读。
        verify(userRepository, times(2)).findById(TENANT_ID, TARGET);
    }

    @Test
    void replaceRolesRejectsTheSameKeyWithDifferentRoles() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW)));
        when(roleRepository.findRoles(TENANT_ID, TARGET)).thenReturn(Set.of(UserRole.USER));

        service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.USER));

        assertThatThrownBy(() -> service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_IDEMPOTENCY_001));
    }

    @Test
    void replaceRolesRejectsAShortIdempotencyKey() {
        assertThatThrownBy(() -> service.replaceRoles(TENANT_ID, CALLER, TARGET, "short", Set.of(UserRole.USER)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.field()).isEqualTo("Idempotency-Key"));
                });
    }

    @Test
    void disablingAnotherUserRevokesSessionsAndAdvancesTheWatermark() {
        when(userRepository.findById(TENANT_ID, TARGET))
                .thenReturn(Optional.of(new AppUserRow(TARGET, "target", UserStatus.DISABLED, null, NOW)));

        UserSummaryData summary =
                service.updateStatus(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, UserStatus.DISABLED);

        assertThat(summary.status()).isEqualTo(UserStatus.DISABLED);
        verify(userRepository).updateStatus(
                TENANT_ID, TARGET, UserStatus.DISABLED, NOW);
        verify(refreshTokenService).revokeAllForUser(TENANT_ID, TARGET);
        verify(accessTokenAuthenticator).revokeAllIssuedBefore(TARGET, NOW);
    }

    @Test
    void enablingAnotherUserKeepsTheExistingSessions() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW)));

        service.updateStatus(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, UserStatus.ACTIVE);

        verify(refreshTokenService, never()).revokeAllForUser(any(), any());
        verify(accessTokenAuthenticator, never()).revokeAllIssuedBefore(any(), any());
    }

    @Test
    void updateStatusRejectsDisablingTheCaller() {
        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, CALLER, CALLER, IDEMPOTENCY_KEY, UserStatus.DISABLED))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.field()).isEqualTo("status"));
                });
        verify(userRepository, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void updateStatusRejectsUnknownTarget() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, UserStatus.DISABLED))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_NOT_FOUND_001));
    }

    @Test
    void updateStatusReplayDoesNotRevokeTwice() {
        when(userRepository.findById(TENANT_ID, TARGET))
                .thenReturn(Optional.of(new AppUserRow(TARGET, "target", UserStatus.DISABLED, null, NOW)));

        service.updateStatus(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, UserStatus.DISABLED);
        service.updateStatus(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, UserStatus.DISABLED);

        verify(userRepository, times(1)).updateStatus(
                TENANT_ID, TARGET, UserStatus.DISABLED, NOW);
        verify(refreshTokenService, times(1)).revokeAllForUser(TENANT_ID, TARGET);
        verify(accessTokenAuthenticator, times(1)).revokeAllIssuedBefore(TARGET, NOW);
    }

    @Test
    void adminCannotReadAUserThatOnlyExistsInAnotherTenant() {
        when(userRepository.findById(FOREIGN_TENANT_ID, TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(FOREIGN_TENANT_ID, TARGET))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_NOT_FOUND_001));
        verify(userRepository, never()).findById(TENANT_ID, TARGET);
    }

    @Test
    void theSameIdempotencyKeyIsScopedToOneTenant() {
        when(userRepository.findById(TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW)));
        when(userRepository.findById(FOREIGN_TENANT_ID, TARGET)).thenReturn(Optional.of(row(TARGET, NOW)));
        when(roleRepository.findRoles(TENANT_ID, TARGET)).thenReturn(Set.of(UserRole.ADMIN));
        when(roleRepository.findRoles(FOREIGN_TENANT_ID, TARGET)).thenReturn(Set.of(UserRole.USER));

        service.replaceRoles(TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN));
        service.replaceRoles(FOREIGN_TENANT_ID, CALLER, TARGET, IDEMPOTENCY_KEY, Set.of(UserRole.USER));

        verify(roleRepository).replaceRoles(TENANT_ID, TARGET, Set.of(UserRole.ADMIN), NOW);
        verify(roleRepository).replaceRoles(FOREIGN_TENANT_ID, TARGET, Set.of(UserRole.USER), NOW);
    }

    private AppUserRow row(UUID id, Instant createdAt) {
        return new AppUserRow(id, "user-" + id.toString().substring(0, 4), UserStatus.ACTIVE, null, createdAt);
    }
}
