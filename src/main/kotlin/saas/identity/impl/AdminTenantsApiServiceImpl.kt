package saas.identity.impl

import io.ktor.server.routing.RoutingCall
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import saas.identity.entity.TenantTable
import saas.identity.shared.api.AdminTenantsApiDelegate
import saas.identity.shared.models.AdminTenantsListTenants200Response
import saas.identity.shared.models.Tenant
import saas.identity.shared.models.TenantStatus

/** admin tenants（本切片仅 list，fastapi tenants_impl.py:53-70 镜像；其余 op 留业务域批）。 */
class AdminTenantsApiServiceImpl(private val db: R2dbcDatabase, private val jwt: JwtIssuer) :
    AdminTenantsApiDelegate {

  override suspend fun adminTenantsListTenants(
      page: Int?,
      pageSize: Int?,
      call: RoutingCall,
  ): AdminTenantsListTenants200Response {
    // 平台域 dev 简化：只要求有效 Bearer，不挂 TenantGuard（springboot 参照）
    requireBearer(call, jwt)
    val p = page ?: 0
    val ps = pageSize ?: 20
    return suspendTransaction(db) {
      val total = TenantTable.selectAll().count()
      val rows =
          TenantTable.selectAll().limit(ps).offset(p.toLong() * ps).toList().map { row ->
            Tenant(
                id = row[TenantTable.id],
                tenantKey = row[TenantTable.tenantKey],
                name = row[TenantTable.name],
                // 读路径 status 恒 active（springboot 参照实现即此语义）
                status = TenantStatus.active,
                createdAt = iso(row[TenantTable.createdAt]),
                updatedAt = iso(row[TenantTable.updatedAt]),
            )
          }
      AdminTenantsListTenants200Response(
          items = rows,
          page = p,
          pageSize = ps,
          total = total,
      )
    }
  }
}
