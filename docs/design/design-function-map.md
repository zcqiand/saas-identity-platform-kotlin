# 设计与功能对齐 — SaaS 身份平台 Kotlin/Ktor 端

> 人填、人评审。机器只检查功能 ID 存在性。
> 回答一个问题：**这个功能子项，落到哪段代码、哪张表、哪个权限码上？**
> 答不上来的行，说明设计没做完，别开工。

## 映射表

| 功能子项 ID | 页面/组件 | 接口 | 数据表 | 权限码 | 设计稿 | 状态 |
|---|---|---|---|---|---|---|
| M00.F01.I01 | —（后端仓） | AdminTenantsApiServiceImpl#adminTenantsListTenants · GET /api/v1/admin/tenants | tenants（TenantTable） | M00.F01.I01 | - | 开发中 |
| M01.F01.I01 | —（后端仓） | MeApiServiceImpl#meWhoami · GET /api/v1/me | sys_users（SysUserTable） | M01.F01.I01 | - | 开发中 |
| M01.F03.I01 | —（后端仓） | MeApiServiceImpl#meListMyTenants · GET /api/v1/me/tenants | tenant_members（TenantMemberTable）+ tenants（TenantTable）+ sys_users（SysUserTable）+ tenant_member_roles（TenantMemberRoleTable） | M01.F03.I01 | - | 开发中 |
| M01.F03.I02 | —（后端仓） | MeApiServiceImpl#meSwitchTenant · POST /api/v1/me/tenants/{tenantId}/switch | tenant_members（TenantMemberTable，归属校验）+ tenants（TenantTable）；换发 JWT 不落表 | M01.F03.I02 | - | 开发中 |
| M01.F04.I01 | —（后端仓） | AuthApiServiceImpl#sessionsLogin · POST /api/v1/auth/login | sys_users（SysUserTable，failed_attempts/locked_until 锁定）+ tenant_members + tenant_applications（订阅交集）+ tenant_member_roles + tenants | M01.F04.I01 | - | 开发中 |
| M01.F04.I06 | —（后端仓） | AuthApiServiceImpl#sessionsLogout · POST /api/v1/auth/logout | 无（无状态 JWT，本地 no-op） | M01.F04.I06 | - | 开发中 |
| M04.F03.I01 | —（后端仓） | OauthApiServiceImpl#oAuthAuthorize · POST /api/v1/oauth/authorize | oauth_codes（OauthCodeTable，一次性 5min）+ oauth_clients（OauthClientTable，redirect_uri 白名单） | M04.F03.I01 | - | 开发中 |
| M04.F03.I02 | —（后端仓） | OauthApiServiceImpl#oAuthToken · POST /api/v1/oauth/token | oauth_codes（验未消费/未过期 → consumed）+ oauth_access_tokens + oauth_refresh_tokens（rotate-once）+ oauth_clients | M04.F03.I02 | - | 开发中 |
| M04.F04.I08 | —（后端仓） | MeApiServiceImpl#meGetMyMenus · GET /api/v1/me/menus | sys_menus（SysMenuTable）+ sys_role_menus（SysRoleMenuTable）+ tenant_member_roles（TenantMemberRoleTable）+ sys_users | M04.F04.I08 | - | 开发中 |

## 约定

1. **权限码 = 功能子项 ID。** 前端按钮的权限判断直接写 ID。
2. 一个接口服务多个子项时，多行重复写。不要为表好看而合并 —— 合并后看不清接口还有没有别的调用方。
3. 状态列必须与功能清单一致。不一致以功能清单为准。

## 评审时问这三个问题

1. 有没有子项没有权限码？→ 那它就是任何人都能点的按钮
2. 有没有一张表被三个以上模块直接写入？→ 边界破了
3. 「开发中」的行里接口和表填了吗？→ 没填就是还在纸上，别报进度
