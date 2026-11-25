package com.docqa.document.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.document.domain.TagName;
import org.junit.jupiter.api.Test;

class TagNormalizerTest {

    @Test
    void leadingTrailingAndRepeatedSpacesCollapseToOneSpace() {
        TagName tag = TagNormalizer.of(" 考研 数学 ");
        assertThat(tag.display()).isEqualTo("考研 数学");
        assertThat(tag.normalized()).isEqualTo("考研 数学");
    }

    @Test
    void theDisplayNameKeepsItsCaseWhileTheComparisonFormIsLowercase() {
        TagName tag = TagNormalizer.of(" Advanced Math ");
        assertThat(tag.display()).isEqualTo("Advanced Math");
        assertThat(tag.normalized()).isEqualTo("advanced math");
    }

    @Test
    void aFullWidthSpaceCountsAsWhitespace() {
        assertThat(TagNormalizer.of("考研　数学").display()).isEqualTo("考研 数学");
    }

    @Test
    void tabsAndNewlinesAreFoldedAway() {
        assertThat(TagNormalizer.of("\n错题\t本 \r\n").display()).isEqualTo("错题 本");
    }

    @Test
    void nullAndBlankInputsProduceAnEmptyName() {
        assertThat(TagNormalizer.of(null).normalized()).isEmpty();
        assertThat(TagNormalizer.of(" ").display()).isEmpty();
    }

    @Test
    void aSingleWordTagKeepsItsFormWithoutTrailingSpace() {
        assertThat(TagNormalizer.of("错题本").display()).isEqualTo("错题本");
    }
}
