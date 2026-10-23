package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// resolveMemoryScope returns the live account and namespace policy to the Rust
// memory writer. The internal token authenticates the caller; the Bearer token
// is verified independently so a caller cannot supply an arbitrary user ID.
func (s *Server) resolveMemoryScope(c *gin.Context) {
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
	c.JSON(http.StatusOK, gin.H{"userId": claims.Subject, "namespace": resolved})
}
