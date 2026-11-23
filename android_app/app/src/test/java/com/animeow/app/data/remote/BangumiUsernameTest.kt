package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class BangumiUsernameTest {
    @Test fun stripsTheMentionPrefixThatBangumiWouldTreatAsPartOfTheName() {
        // 线上故障原样：@599157 被编码成 %40599157，Bangumi 侧必然 404。
        assertEquals("599157", normalizeBangumiUsername("@599157"))
        assertEquals("599157", normalizeBangumiUsername("  @599157  "))
    }

    @Test fun acceptsAProfileLinkAndKeepsOnlyTheName() {
        assertEquals("599157", normalizeBangumiUsername("https://bgm.tv/user/599157"))
        assertEquals("xunlys", normalizeBangumiUsername("http://bangumi.tv/user/xunlys"))
        assertEquals("xunlys", normalizeBangumiUsername("bgm.tv/user/xunlys"))
        assertEquals("599157", normalizeBangumiUsername("https://bgm.tv/user/599157/collections"))
        assertEquals("xunlys", normalizeBangumiUsername("https://www.bgm.tv/user/xunlys?tab=anime"))
    }

    @Test fun keepsPlainNamesAndUidsUntouched() {
        assertEquals("599157", normalizeBangumiUsername("599157"))
        assertEquals("xunlys", normalizeBangumiUsername("xunlys"))
        assertEquals("some-user_01", normalizeBangumiUsername("some-user_01"))
    }

    @Test fun dropsTrailingPathCopiedAlongWithTheName() {
        assertEquals("599157", normalizeBangumiUsername("599157/collections"))
        assertEquals("599157", normalizeBangumiUsername("599157?subject_type=2"))
    }

    @Test fun reportsEmptyInputAsEmptySoCallersCanAskAgain() {
        assertEquals("", normalizeBangumiUsername(""))
        assertEquals("", normalizeBangumiUsername("   "))
        assertEquals("", normalizeBangumiUsername("@"))
    }
}
