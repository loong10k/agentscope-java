package httpapi

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// resolveMemoryScope returns the live account and namespace policy to the Rust
// memory writer. The internal token authenticates the caller; the Bearer token
// is verified independently so a caller cannot supply an arbitrary user ID.
func (s *Server) resolveMemoryScope(c *gin.Context) {
	// 这个入口把实时账户和空间策略交给 Rust 写入端；Bearer 只证明用户身份，
	// 还必须验证调用方持有内部凭据，且未配置凭据时不可退化为公开入口。
	if s.internalToken == "" {
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "memory scope internal token unavailable"})
		return
	}
	provided := c.GetHeader("X-Builder-Internal-Token")
	if subtle.ConstantTimeCompare([]byte(provided), []byte(s.internalToken)) != 1 {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid internal token"})
		return
	}
	if s.product == nil || s.store == nil {
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "memory scope unavailable"})
		return
	}
	authorization := c.GetHeader("Authorization")
	if !strings.HasPrefix(authorization, "Bearer ") {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
		return
	}
	claims, err := s.product.VerifyAccountToken(c.Request.Context(), strings.TrimPrefix(authorization, "Bearer "))
	if err != nil || claims.Subject == "" {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
		return
	}
	personal, err := s.ensurePersonalNamespace(c.Request.Context(), claims.Subject)
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	tenant, name := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = c.GetHeader("X-AgentScope-Tenant")
	}
	if name == "" {
		name = c.GetHeader("X-AgentScope-Namespace")
	}
	if tenant == "" {
		tenant = personal.Tenant
	}
	if name == "" {
		name = personal.Name
	}
	namespace, err := s.store.Access().GetNamespace(c.Request.Context(), tenant, name)
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	if len(namespace.Roles(claims.Subject)) == 0 {
		c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "namespace not found"})
		return
	}
	resolved := *namespace
	if resolved.Members == nil {
		resolved.Members = map[string][]string{}
	}
	// 向 Rust 提供本次请求实时核验的具名共享空间，而不是让浏览器声明成员资格。
	// 分页取尽并设置上限；超限或控制面读取失败时整次请求失败，不能返回不完整证明。
	workspaces := []string{}
	for offset := 0; offset < 10000; offset += 500 {
		items, listErr := s.store.Access().ListNamespaces(c.Request.Context(), s.defaultTenant, claims.Subject, 500, offset)
		if listErr != nil {
			s.accessFailure(c, listErr)
			return
		}
		for _, item := range items {
			if item.Kind == "shared" && len(item.Roles(claims.Subject)) > 0 {
				workspaces = append(workspaces, item.Tenant+"/"+item.Name)
			}
		}
		if len(items) < 500 {
			c.JSON(http.StatusOK, gin.H{"userId": claims.Subject, "namespace": resolved, "workspaces": workspaces})
			return
		}
	}
	c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "workspace membership limit exceeded"})
}
