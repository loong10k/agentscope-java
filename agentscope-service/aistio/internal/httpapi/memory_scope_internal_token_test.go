package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// 内部记忆空间查询在进入账户校验之前必须先验证宿主凭据。
func TestMemoryScopeRequiresConfiguredInternalToken(t *testing.T) {
	for _, tc := range []struct {
		name       string
		configured string
		provided   string
		wantStatus int
		wantBody   string
	}{
		{"missing configuration", "", "", http.StatusServiceUnavailable, "memory scope internal token unavailable"},
		{"missing header", "runtime-secret", "", http.StatusUnauthorized, "invalid internal token"},
		{"wrong header", "runtime-secret", "wrong-secret", http.StatusUnauthorized, "invalid internal token"},
		{"valid header passes token gate", "runtime-secret", "runtime-secret", http.StatusServiceUnavailable, "memory scope unavailable"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := &Server{internalToken: tc.configured}
			router := gin.New()
			router.GET("/api/internal/memory-scope", server.resolveMemoryScope)
			request := httptest.NewRequest(http.MethodGet, "/api/internal/memory-scope", nil)
			request.Header.Set("Authorization", "Bearer user-token")
			if tc.provided != "" {
				request.Header.Set("X-Builder-Internal-Token", tc.provided)
			}
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			if response.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d: %s", response.Code, tc.wantStatus, response.Body.String())
			}
			if !strings.Contains(response.Body.String(), tc.wantBody) {
				t.Fatalf("body = %q, want %q", response.Body.String(), tc.wantBody)
			}
		})
	}
}
