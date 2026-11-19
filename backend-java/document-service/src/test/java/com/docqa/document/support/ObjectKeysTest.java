package com.docqa.document.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectKeysTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void sourceKeyEmbedsTenantAsFirstSegment() {
        String key = ObjectKeys.source(TENANT_ID, USER_ID, DOCUMENT_ID);

        assertThat(key).isEqualTo("documents/" + TENANT_ID + "/" + USER_ID + "/" + DOCUMENT_ID + "/source");
        assertThat(key.split("/")).containsExactly(
                "documents", TENANT_ID.toString(), USER_ID.toString(), DOCUMENT_ID.toString(), "source");
    }

    @Test
    void differentTenantsNeverShareAnObjectKey() {
        UUID otherTenant = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        assertThat(ObjectKeys.source(TENANT_ID, USER_ID, DOCUMENT_ID))
                .isNotEqualTo(ObjectKeys.source(otherTenant, USER_ID, DOCUMENT_ID));
    }
}
