package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
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

// 具名 WORKSPACE 分享仅能引用当前存在、未归档的共享空间，且只开放给内部调用方。
func TestMemoryWorkspaceTargetRequiresLiveSharedNamespace(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	for _, namespace := range []*model.Namespace{
		{Tenant: "default", Name: "team-a", DisplayName: "Team A", Kind: "shared", Owner: "alice"},
		{Tenant: "default", Name: "personal-alice", DisplayName: "Personal", Kind: "personal", Owner: "alice"},
		{Tenant: "default", Name: "archived", DisplayName: "Archived", Kind: "shared", Owner: "alice", Archived: true},
	} {
		if _, err := st.Access().PutNamespace(ctx, namespace, 0, "alice"); err != nil {
			t.Fatal(err)
		}
	}
	server := &Server{store: st, internalToken: "runtime-secret"}
	router := gin.New()
	router.GET("/api/internal/memory-workspace-target", server.resolveMemoryWorkspaceTarget)
	for _, tc := range []struct {
		path, token string
		status      int
	}{
		{"?tenant=default&namespace=team-a", "runtime-secret", http.StatusOK},
		{"?tenant=default&namespace=team-a", "wrong", http.StatusUnauthorized},
		{"?tenant=default&namespace=personal-alice", "runtime-secret", http.StatusNotFound},
		{"?tenant=default&namespace=archived", "runtime-secret", http.StatusNotFound},
		{"?tenant=other&namespace=team-a", "runtime-secret", http.StatusNotFound},
		{"?tenant=default&namespace=missing", "runtime-secret", http.StatusNotFound},
	} {
		request := httptest.NewRequest(http.MethodGet, "/api/internal/memory-workspace-target"+tc.path, nil)
		request.Header.Set("X-Builder-Internal-Token", tc.token)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		if response.Code != tc.status {
			t.Errorf("%s: status=%d want=%d body=%s", tc.path, response.Code, tc.status, response.Body.String())
		}
		if tc.status == http.StatusOK && !strings.Contains(response.Body.String(), `"workspaceId":"default/team-a"`) {
			t.Errorf("invalid workspace identity: %s", response.Body.String())
		}
	}
}

// 运行期证明覆盖所有租户，且排除非成员、个人空间和已归档工作区。
func TestCurrentMemoryWorkspacesCoversMemberNamespacesAcrossTenants(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	for _, namespace := range []*model.Namespace{
		{Tenant: "default", Name: "team-a", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}}},
		{Tenant: "other", Name: "team-a", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}}},
		{Tenant: "other", Name: "team-b", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}}},
		{Tenant: "other", Name: "not-member", Kind: "shared", Owner: "alice"},
		{Tenant: "other", Name: "personal", Kind: "personal", Owner: "bob"},
		{Tenant: "other", Name: "archived", Kind: "shared", Owner: "bob", Archived: true},
	} {
		if _, err := st.Access().PutNamespace(ctx, namespace, 0, "alice"); err != nil {
			t.Fatal(err)
		}
	}
	server := &Server{store: st, defaultTenant: "default"}
	workspaces, err := server.currentMemoryWorkspaces(ctx, "bob")
	if err != nil {
		t.Fatal(err)
	}
	if len(workspaces) != 3 || workspaces[0] != "default/team-a" || workspaces[1] != "other/team-a" || workspaces[2] != "other/team-b" {
		t.Fatalf("workspaces = %v, want all tenant-qualified memberships", workspaces)
	}
}
