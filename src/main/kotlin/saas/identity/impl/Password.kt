package saas.identity.impl

/** plain: 直通 + 非 plain: 存储 invalid（家族 dev 口径，spec §3 人批 2026-10-16）。 */
object Password {

  fun matches(raw: String, stored: String): Boolean = stored == "plain:$raw"
}
