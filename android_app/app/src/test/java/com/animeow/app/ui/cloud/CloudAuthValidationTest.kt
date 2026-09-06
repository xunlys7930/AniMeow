package com.animeow.app.ui.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudAuthValidationTest {
    @Test
    fun loginOnlyRequiresUsernameAndValidPassword() {
        assertNull(validateCloudAuthForm(AuthMode.LOGIN, "user", "123456", "", ""))
    }

    @Test
    fun registrationRequiresMatchingConfirmationWithoutInviteCode() {
        assertEquals(
            "两次输入的密码不一致",
            validateCloudAuthForm(AuthMode.REGISTER, "user", "123456", "654321", "CODE"),
        )
        assertNull(validateCloudAuthForm(AuthMode.REGISTER, "user", "123456", "123456", ""))
    }

    @Test
    fun legacyInviteIsOptionalForPasswordReset() {
        assertNull(validateCloudAuthForm(AuthMode.RESET, "user", "123456", "123456", ""))
    }
}
