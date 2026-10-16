package io.xr.harness

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.impl.Password

class PasswordTest {

  @Test
  fun plainPrefixDirectCompare() {
    assertTrue(Password.matches("dev123456", "plain:dev123456"))
    assertFalse(Password.matches("wrong", "plain:dev123456"))
  }

  @Test
  fun nonPlainStorageIsInvalidSemantics() {
    // 家族 dev 库全 plain:；bcrypt/其他存储形态一律 invalid，不泄露存储形态（spec §3 人批口径）
    assertFalse(Password.matches("dev123456", "\$2a\$10\$whatever"))
    assertFalse(Password.matches("dev123456", ""))
  }
}
